package app.nota.data.backup

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stage of a user-initiated restore (Phase 2.5, F2).
 *
 * Every non-idle stage is a VISIBLE outcome or a resumable intent:
 * there is deliberately no stage in which a picked backup can end
 * without the user either seeing a summary, a passphrase prompt, or a
 * typed [Failed] message. The temp file referenced by [tempFilePath]
 * is owned by the store: it is deleted exactly when the restore is
 * cancelled, completed, or fails unreadably — never by a lock cycle.
 */
sealed interface PendingRestoreState {
    data object Idle : PendingRestoreState

    /** SAF result received; the picked stream is being copied to the
     *  cache-dir temp file on the store's own scope. */
    data object Copying : PendingRestoreState

    /** Temp copy exists, not yet read. Survives lock cycles: whichever
     *  BackupSection composition comes back after unlock claims it. */
    data class NeedsRead(val tempFilePath: String) : PendingRestoreState

    data class Reading(val tempFilePath: String) : PendingRestoreState

    /** The archive is encrypted. [error] is set when a supplied
     *  passphrase was rejected, so the re-ask is visible, not silent. */
    data class NeedsPassphrase(
        val tempFilePath: String,
        val error: String? = null,
    ) : PendingRestoreState

    /** Read complete; the summary dialog is (or will be, after unlock)
     *  showing. The live [BackupReader.Handle] is held by the store. */
    data class SummaryReady(val tempFilePath: String) : PendingRestoreState

    data class Applying(val tempFilePath: String) : PendingRestoreState

    /** Terminal success — shown in Settings until acknowledged. */
    data class Completed(val message: String) : PendingRestoreState

    /** Terminal, typed failure — shown in Settings until acknowledged.
     *  Restore attempts NEVER end anywhere else silently. */
    data class Failed(val message: String) : PendingRestoreState
}

/**
 * App-scoped holder for an in-flight restore (Phase 2.5, F2).
 *
 * Root cause this fixes: with App Lock on and Lock-after = Immediately,
 * returning from the SAF picker re-locks the app (ProcessLifecycleOwner
 * onStart → AppLockManager.maybeRelock, timeout 0), MainActivity swaps
 * the whole nav shell for the lock screen, and BackupSection's
 * composition — which held the picked URI's continuation coroutine,
 * the temp-file reference, the passphrase prompt and the summary in
 * `remember` state — is destroyed. The restore died with it, silently.
 *
 * This store outlives that gate: it is a process-wide singleton (see
 * [shared]; Koin registration is an optional coordinator follow-up),
 * owns its own [scope] for the copy/read/apply work, and holds the
 * stage as a [StateFlow]. Lock cycles change nothing here; after
 * unlock, the freshly composed BackupSection observes the same stage
 * and resumes — summary, passphrase prompt, or outcome message.
 *
 * Nothing here exposes note content: the store holds only a temp-file
 * path, a reader handle (manifest counts + staged payloads) and
 * messages. BackupSection renders stages only while it is composed,
 * which — by MainActivity's gate — is only while unlocked.
 */
class PendingRestoreStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val lock = Any()

    private val _state = MutableStateFlow<PendingRestoreState>(PendingRestoreState.Idle)
    val state: StateFlow<PendingRestoreState> = _state.asStateFlow()

    @Volatile
    private var handle: BackupReader.Handle? = null

    /** The read handle for the [PendingRestoreState.SummaryReady] /
     *  [PendingRestoreState.Applying] stages (manifest rendering +
     *  apply). Owned and closed by the store. */
    val summaryHandle: BackupReader.Handle? get() = handle

    /**
     * Stages a freshly picked backup. [copy] runs on the store scope
     * (NOT a composition scope), so the copy finishes even if a lock
     * cycle destroys the settings composition mid-copy. Ignored when a
     * restore is already in flight.
     */
    fun stageCopy(copy: () -> File) {
        synchronized(lock) {
            when (_state.value) {
                is PendingRestoreState.Idle,
                is PendingRestoreState.Completed,
                is PendingRestoreState.Failed,
                -> _state.value = PendingRestoreState.Copying
                else -> return
            }
        }
        scope.launch {
            try {
                val file = copy()
                synchronized(lock) {
                    if (_state.value is PendingRestoreState.Copying) {
                        _state.value = PendingRestoreState.NeedsRead(file.absolutePath)
                    } else {
                        // Cancelled while copying: the staged copy is
                        // ours alone — delete it rather than orphan it.
                        file.delete()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) {
                    if (_state.value is PendingRestoreState.Copying) {
                        _state.value = PendingRestoreState.Failed(
                            e.message ?: "That backup couldn't be read.",
                        )
                    }
                }
            }
        }
    }

    /**
     * Claims a pending read (from [PendingRestoreState.NeedsRead] with
     * no passphrase, or from [PendingRestoreState.NeedsPassphrase] with
     * one) and runs [read] on the store scope. Outcomes:
     * - success → [PendingRestoreState.SummaryReady], handle held here;
     * - [BackupKeyNeededError] → [PendingRestoreState.NeedsPassphrase];
     * - [BackupUnlockError] → back to NeedsPassphrase WITH the error,
     *   so a wrong passphrase re-asks visibly and the file survives;
     * - anything else → temp file deleted, typed [PendingRestoreState.Failed].
     * A call in any other stage is a no-op (duplicate claims from
     * recomposition can never double-read).
     */
    fun performRead(read: suspend () -> BackupReader.Handle) {
        val path = synchronized(lock) {
            val current = _state.value
            val p = when (current) {
                is PendingRestoreState.NeedsRead -> current.tempFilePath
                is PendingRestoreState.NeedsPassphrase -> current.tempFilePath
                else -> return
            }
            _state.value = PendingRestoreState.Reading(p)
            p
        }
        scope.launch {
            try {
                val result = read()
                synchronized(lock) {
                    if (_state.value is PendingRestoreState.Reading) {
                        handle = result
                        _state.value = PendingRestoreState.SummaryReady(path)
                    } else {
                        result.close()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupKeyNeededError) {
                transitionFromReading(
                    PendingRestoreState.NeedsPassphrase(path),
                )
            } catch (e: BackupUnlockError) {
                transitionFromReading(
                    PendingRestoreState.NeedsPassphrase(
                        path,
                        e.message ?: "That passphrase didn't open the backup.",
                    ),
                )
            } catch (e: Exception) {
                File(path).delete()
                transitionFromReading(
                    PendingRestoreState.Failed(e.message ?: "That backup couldn't be read."),
                )
            }
        }
    }

    /**
     * Claims [PendingRestoreState.SummaryReady] and runs [apply] (which
     * returns the user-visible success message) on the store scope.
     * Success and failure BOTH end in a visible terminal state; the
     * handle is closed and the temp file deleted either way, matching
     * the pre-2.5 finally-block semantics — minus the silence.
     */
    fun performApply(apply: suspend (BackupReader.Handle) -> String) {
        val (path, liveHandle) = synchronized(lock) {
            val current = _state.value
            if (current !is PendingRestoreState.SummaryReady) return
            val h = handle ?: return
            _state.value = PendingRestoreState.Applying(current.tempFilePath)
            current.tempFilePath to h
        }
        scope.launch {
            try {
                val message = apply(liveHandle)
                synchronized(lock) {
                    if (_state.value is PendingRestoreState.Applying) {
                        _state.value = PendingRestoreState.Completed(message)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) {
                    if (_state.value is PendingRestoreState.Applying) {
                        _state.value = PendingRestoreState.Failed(
                            e.message ?: "Restore failed.",
                        )
                    }
                }
            } finally {
                synchronized(lock) { if (handle === liveHandle) handle = null }
                liveHandle.close()
                File(path).delete()
            }
        }
    }

    /** User cancelled at the passphrase prompt or the summary dialog:
     *  close any handle, delete the temp copy, return to Idle. A read
     *  or copy still in flight discards its own result (its completion
     *  guard sees the stage has moved on). */
    fun cancel() {
        synchronized(lock) {
            val current = _state.value
            val path = when (current) {
                is PendingRestoreState.NeedsRead -> current.tempFilePath
                is PendingRestoreState.Reading -> current.tempFilePath
                is PendingRestoreState.NeedsPassphrase -> current.tempFilePath
                is PendingRestoreState.SummaryReady -> current.tempFilePath
                is PendingRestoreState.Applying -> current.tempFilePath
                else -> null
            }
            handle?.close()
            handle = null
            if (path != null) File(path).delete()
            _state.value = PendingRestoreState.Idle
        }
    }

    /** Dismisses a terminal outcome ([PendingRestoreState.Completed] /
     *  [PendingRestoreState.Failed]) once the user has seen it. */
    fun acknowledge() {
        synchronized(lock) {
            when (_state.value) {
                is PendingRestoreState.Completed,
                is PendingRestoreState.Failed,
                -> _state.value = PendingRestoreState.Idle
                else -> Unit
            }
        }
    }

    private fun transitionFromReading(to: PendingRestoreState) {
        synchronized(lock) {
            if (_state.value is PendingRestoreState.Reading) _state.value = to
        }
    }

    companion object {
        /** Process-wide instance: app-scoped by construction, so it
         *  outlives the app-lock gate's composition swap by design. */
        val shared: PendingRestoreStore = PendingRestoreStore()
    }
}
