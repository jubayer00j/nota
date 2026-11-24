package app.nota.data.importing

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-scoped holder for an in-flight (or just-finished) import — the
 * PendingRestoreStore pattern. Picking a file can background the app,
 * and App Lock's relock on return destroys the Settings composition:
 * the staging copy, the run, its progress and the terminal summary
 * therefore live here, in process scope, and the Settings section only
 * renders them. An import never ends silently: the terminal state is
 * Completed(summary) or Failed(message), held until dismissed.
 */
sealed interface PendingImportState {
    data object Idle : PendingImportState
    data object Staging : PendingImportState
    data class Running(val done: Int, val total: Int) : PendingImportState
    data class Completed(val summary: ImportSummary) : PendingImportState
    data class Failed(val message: String) : PendingImportState
}

/** What the UI staged: a temp dir (deleted at the terminal transition)
 *  plus either a Keep ZIP or the picked file set. [skippedFiles] holds
 *  picks the UI already rejected before copying (unsupported type, or
 *  over the staging cap) — they still belong in the report, so the
 *  engine folds them into the outcome's skipped files. */
class StagedImport(
    val tempDir: File,
    val keepZip: File?,
    val files: List<StagedImportFile>,
    val skippedFiles: List<SkippedImportFile> = emptyList(),
)

class PendingImportStore(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val _state = MutableStateFlow<PendingImportState>(PendingImportState.Idle)
    val state: StateFlow<PendingImportState> = _state.asStateFlow()
    private val lock = Mutex()

    val isBusy: Boolean
        get() = _state.value is PendingImportState.Staging ||
            _state.value is PendingImportState.Running

    /**
     * Runs [stage] (the SAF copy into a temp dir) and then [run] (parse
     * + engine) on the store's scope. A second call while one import is
     * staging/running is ignored — the UI disables its buttons then
     * anyway; this is the belt-and-braces guard.
     */
    fun stageAndRun(
        stage: () -> StagedImport,
        run: suspend (StagedImport, (Int, Int) -> Unit) -> ImportSummary,
    ) {
        scope.launch {
            val accepted = lock.withLock {
                if (isBusy) {
                    false
                } else {
                    _state.value = PendingImportState.Staging
                    true
                }
            }
            if (!accepted) return@launch
            val input = try {
                stage()
            } catch (e: Exception) {
                lock.withLock {
                    _state.value = PendingImportState.Failed(
                        "Couldn't read the file you picked." +
                            (e.message?.let { " $it" } ?: ""),
                    )
                }
                return@launch
            }
            lock.withLock { _state.value = PendingImportState.Running(0, 0) }
            try {
                val summary = run(input) { done, total -> setProgress(done, total) }
                lock.withLock { _state.value = PendingImportState.Completed(summary) }
            } catch (e: Exception) {
                lock.withLock {
                    _state.value = PendingImportState.Failed(
                        e.message ?: "Something went wrong during the import.",
                    )
                }
            } finally {
                input.tempDir.deleteRecursively()
            }
        }
    }

    private fun setProgress(done: Int, total: Int) {
        while (true) {
            val current = _state.value
            if (current !is PendingImportState.Running) return
            if (_state.compareAndSet(current, PendingImportState.Running(done, total))) return
        }
    }

    /** Dismisses a terminal state (the summary / failure message). */
    fun acknowledge() {
        scope.launch {
            lock.withLock {
                if (_state.value is PendingImportState.Completed ||
                    _state.value is PendingImportState.Failed
                ) {
                    _state.value = PendingImportState.Idle
                }
            }
        }
    }

    companion object {
        /** Process-wide instance the Settings section renders. */
        val shared = PendingImportStore()
    }
}
