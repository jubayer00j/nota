package app.nota.data.security

import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.nota.data.preferences.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Outcome of one unlock attempt on the lock screen. */
sealed interface UnlockResult {
    data object Success : UnlockResult
    data object Wrong : UnlockResult

    /** Attempts are refused until [untilMillis] (epoch millis). */
    data class LockedOut(val untilMillis: Long) : UnlockResult
}

/**
 * App Lock lifecycle + verification (Phase 2a, Koin single).
 *
 * - Cold start: [isLocked] starts true and [ready] false; once the first
 *   persisted settings emission lands, ready flips and a disabled lock
 *   drops isLocked. MainActivity renders neither notes nor the lock
 *   screen until ready, so notes can never flash on a locked launch.
 * - Backgrounding is tracked with ProcessLifecycleOwner (process-level,
 *   so in-app navigation and config changes never count): the stop time
 *   is recorded, and on the next start the app re-locks when the elapsed
 *   time reaches the configured timeout — timeout 0 means ANY
 *   backgrounding re-locks.
 * - Unlock attempts verify against the persisted PBKDF2 verifiers and
 *   own the 1.x lockout bookkeeping (5+ failures → exponential lockout).
 *   Settings re-authorisation ([verifyForSettings]) is a pure check: it
 *   respects an active lockout but never increments the counter — the
 *   app is already unlocked in that flow.
 *
 * HONEST POSTURE: App Lock is a privacy barrier, not encryption. The
 * hashes here only gate the UI; note contents are untouched by it.
 */
class AppLockManager(
    private val repository: PreferencesRepository,
    private val biometricStore: AppLockBiometricStore,
    /** Clock for the in-memory background window (PR #55, Argon
     *  F-09): monotonic elapsed-realtime, so wall-clock changes
     *  (NTP sync, a manual time change) can neither extend the
     *  unlocked window indefinitely nor force a spurious re-lock.
     *  A constructor seam so tests control the window. Persisted
     *  lockout deadlines stay wall-clock — they must survive a
     *  reboot, and a monotonic clock resets there. */
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() },
    /** Fired at the single moment the lock ENGAGES (PR #55,
     *  Argon F-07): production wipes the decrypted attachment
     *  session cache, whose plaintext must not outlive an
     *  unlocked session. Cold start needs no hook — the lock
     *  starts engaged and NotaApplication wipes directly. */
    private val onLockEngaged: () -> Unit = {},
    /** Pause before re-observing the lock settings after the
     *  repository flow completes on a transient read error (see
     *  init). A constructor seam so tests can run the retry loop
     *  on a virtual clock instead of waiting in real time. */
    private val reobserveDelayMillis: Long = REOBSERVE_DELAY_MILLIS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _settings = MutableStateFlow(AppLockSettings())
    val settings: StateFlow<AppLockSettings> = _settings.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /**
     * True while the persisted lock settings cannot be read (the
     * repository flow emitted null). The manager fails CLOSED in this
     * state: ready, still locked, and MainActivity shows an explanatory
     * locked screen instead of the notes.
     */
    private val _settingsUnreadable = MutableStateFlow(false)
    val settingsUnreadable: StateFlow<Boolean> = _settingsUnreadable.asStateFlow()

    /** Serialises unlock attempts so the failed-attempt counter can
     *  never lose an increment to a concurrent submission (Codex, PR #7). */
    private val attemptMutex = Mutex()

    /** When the process last backgrounded, on the MONOTONIC
     *  elapsed-realtime clock (PR #55, Argon F-09) — in-memory
     *  only, never persisted: the window it measures is a
     *  within-process convenience, while persisted lockout
     *  deadlines (which must survive reboots) stay wall-clock. */
    @Volatile
    private var backgroundedAtElapsedMillis: Long? = null

    init {
        scope.launch {
            // Observation must survive the repository flow
            // COMPLETING: its fail-closed catch emits null on a
            // transient IOException and then terminates (catch
            // semantics), so collecting exactly once would go blind
            // at exactly the moment the store later recovers — the
            // recovery screen's successful credential write would
            // land unobserved and the app would sit on the
            // settings-unreadable screen until restart (Codex
            // review, PR #33). Re-collect after a pause instead: a
            // fresh collection re-reads the store, so a transient
            // error in the past yields the real settings, and a
            // persistently failing store is retried gently, never
            // in a hot loop. Fail-closed throughout: nothing here
            // clears settingsUnreadable — only a real emission
            // does. (The production store's flow completes ONLY
            // via that catch; DataStore.data itself never ends.)
            while (true) {
                repository.appLockSettings.collect { loaded ->
                    if (loaded == null) {
                        // Fail closed: an unreadable store is NOT "lock off".
                        _settingsUnreadable.value = true
                        _ready.value = true
                        return@collect
                    }
                    _settingsUnreadable.value = false
                    _settings.value = loaded
                    if (!_ready.value) {
                        _ready.value = true
                        if (!loaded.isEnabled) _isLocked.value = false
                    } else if (!loaded.isEnabled) {
                        // Lock turned off (or last method removed) while running.
                        _isLocked.value = false
                    }
                }
                delay(reobserveDelayMillis)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    onAppBackgrounded()
                }

                override fun onStart(owner: LifecycleOwner) {
                    onAppForegrounded()
                }
            },
        )
    }

    /** Process backgrounded: stamp the monotonic window start.
     *  Internal (not private) so tests can drive the lifecycle
     *  without a real ProcessLifecycleOwner. */
    internal fun onAppBackgrounded() {
        backgroundedAtElapsedMillis = elapsedRealtimeMillis()
    }

    /** Process foregrounded: re-lock when the window has elapsed. */
    internal fun onAppForegrounded() {
        maybeRelock()
    }

    private fun maybeRelock() {
        val current = _settings.value
        if (!current.isEnabled || _isLocked.value) {
            backgroundedAtElapsedMillis = null
            return
        }
        val backgroundedAt = backgroundedAtElapsedMillis ?: return
        backgroundedAtElapsedMillis = null
        val elapsed = elapsedRealtimeMillis() - backgroundedAt
        val timeoutMs = current.lockTimeoutSeconds.toLong() * 1_000L
        if (timeoutMs <= 0L || elapsed >= timeoutMs) {
            _isLocked.value = true
            onLockEngaged()
        }
    }

    /**
     * CRYPTO-BOUND biometric unlock (Phase 2.5, replacing the old
     * bare-boolean `unlockWithBiometric()`): [authorisedCipher] is the
     * cipher BiometricPrompt authorised via its CryptoObject, obtained
     * from [AppLockBiometricStore.openCipherResult]. The app unlocks ONLY
     * when that cipher actually decrypts the sealed token
     * ([AppLockBiometricStore.verifyTokenResult]) — a prompt success without
     * a working crypto result never reaches here as a proof, and a
     * cipher that cannot decrypt the token changes nothing.
     * Phase 2.6: returns the typed [StepResult] so the lock screen
     * can SHOW why a fingerprint attempt failed instead of refusing
     * silently. Ok means the app is now unlocked.
     */
    fun unlockWithBiometric(authorisedCipher: javax.crypto.Cipher): StepResult<Unit> {
        val current = _settings.value
        if (!current.isEnabled || !current.biometricEnabled) {
            return StepResult.Failed(
                BiometricStep.DECRYPT,
                "fingerprint unlock is not enabled",
            )
        }
        if (System.currentTimeMillis() < current.lockoutUntil) {
            return StepResult.Failed(BiometricStep.DECRYPT, "app is in a temporary lockout")
        }
        val verified = biometricStore.verifyTokenResult(authorisedCipher)
        if (verified is StepResult.Failed) return verified
        _isLocked.value = false
        backgroundedAtElapsedMillis = null
        scope.launch {
            attemptMutex.withLock { repository.setAppLockFailedAttempts(0, 0L) }
        }
        return StepResult.Ok(Unit)
    }

    /**
     * Crypto-bound biometric check for Settings re-authorisation:
     * same token proof as [unlockWithBiometric], but never unlocks
     * the app and never mutates the attempt counters.
     */
    fun verifyBiometricForSettings(authorisedCipher: javax.crypto.Cipher): StepResult<Unit> {
        val current = _settings.value
        if (!current.isEnabled || !current.biometricEnabled) {
            return StepResult.Failed(
                BiometricStep.DECRYPT,
                "fingerprint unlock is not enabled",
            )
        }
        if (System.currentTimeMillis() < current.lockoutUntil) {
            return StepResult.Failed(BiometricStep.DECRYPT, "app is in a temporary lockout")
        }
        return biometricStore.verifyTokenResult(authorisedCipher)
    }

    /**
     * One credential unlock attempt. Serialised on [attemptMutex]: the
     * read-modify-write of the failed-attempt counter must be atomic,
     * or two in-flight submissions could snapshot the same count and a
     * failure would be lost (delaying the lockout). PBKDF2 runs on
     * Dispatchers.Default — the password derivation (600k iterations)
     * must never block the UI.
     */
    suspend fun attemptUnlock(
        method: AppLockMethod,
        canonicalSecret: String,
    ): UnlockResult = attemptMutex.withLock {
        attemptUnlockLocked(method, canonicalSecret)
    }

    private suspend fun attemptUnlockLocked(
        method: AppLockMethod,
        canonicalSecret: String,
    ): UnlockResult {
        val current = _settings.value
        val now = System.currentTimeMillis()
        if (now < current.lockoutUntil) return UnlockResult.LockedOut(current.lockoutUntil)
        val credential = current.credentialFor(method) ?: return UnlockResult.Wrong
        val iterations = AppLockCrypto.iterationsFor(method)
        val ok = withContext(Dispatchers.Default) {
            LockPerf.measure("app-lock verify", iterations) {
                AppLockCrypto.verify(
                    canonicalSecret,
                    credential.salt,
                    credential.hash,
                    iterations,
                )
            }
        }
        return if (ok) {
            repository.setAppLockFailedAttempts(0, 0L)
            _isLocked.value = false
            backgroundedAtElapsedMillis = null
            UnlockResult.Success
        } else {
            val attempts = current.failedAttempts + 1
            val duration = AppLockCrypto.lockoutDurationMs(attempts)
            val until = if (duration > 0) System.currentTimeMillis() + duration else 0L
            repository.setAppLockFailedAttempts(attempts, until)
            if (until > 0) UnlockResult.LockedOut(until) else UnlockResult.Wrong
        }
    }

    /**
     * Pure credential check for Settings re-authorisation. Respects an
     * active lockout; never mutates the attempt counters.
     */
    suspend fun verifyForSettings(method: AppLockMethod, canonicalSecret: String): Boolean {
        val current = _settings.value
        if (System.currentTimeMillis() < current.lockoutUntil) return false
        val credential = current.credentialFor(method) ?: return false
        val iterations = AppLockCrypto.iterationsFor(method)
        return withContext(Dispatchers.Default) {
            LockPerf.measure("app-lock settings verify", iterations) {
                AppLockCrypto.verify(
                    canonicalSecret,
                    credential.salt,
                    credential.hash,
                    iterations,
                )
            }
        }
    }

    // ---- Mutations (callers gate these behind re-authorisation) -------

    /**
     * Sets (or replaces) one method's credential. First method = lock on.
     *
     * Throws if the credential cannot be persisted: the repository's
     * lock write reports an IO failure instead of swallowing it, and
     * the setup flows (Settings, and the settings-unreadable recovery
     * in MainActivity) depend on that — both keep their form open
     * with an error on failure rather than closing on a save that
     * never landed (Codex review, PR #33).
     */
    suspend fun setCredential(method: AppLockMethod, canonicalSecret: String) {
        val credential = withContext(Dispatchers.Default) {
            val salt = AppLockCrypto.generateSalt()
            AppLockCredential(
                salt = salt,
                hash = AppLockCrypto.deriveKeyHash(
                    canonicalSecret,
                    salt,
                    AppLockCrypto.iterationsFor(method),
                ),
            )
        }
        repository.setAppLockCredential(method, credential)
    }

    /** Removes one method; removing the last one disables App Lock. */
    suspend fun removeCredential(method: AppLockMethod) {
        repository.removeAppLockCredential(method)
        // Read the post-write state via the collector's next emission is
        // racy here; reason from the snapshot instead: if this was the
        // last method, the biometric flag must not survive on its own.
        val remaining = _settings.value.enabledMethods - method
        if (remaining.isEmpty()) {
            repository.setAppLockBiometricEnabled(false)
            scope.launch { biometricStore.clear() }
            _isLocked.value = false
        }
    }

    /** Turns App Lock fully off (all slots + biometric flag cleared). */
    suspend fun disableAll() {
        repository.clearAppLock()
        // PR #55 (Argon F-09): the sealed biometric token must die
        // with the lock — the same rule setBiometricEnabled(false)
        // and removeCredential already honour. Leaving it behind
        // meant a stale token outlived the feature that owns it.
        biometricStore.clear()
        _isLocked.value = false
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        repository.setAppLockBiometricEnabled(enabled)
        // Disabling fingerprint must destroy its proof material too:
        // a stale sealed token must never outlive the toggle.
        if (!enabled) biometricStore.clear()
    }

    suspend fun setLockTimeoutSeconds(seconds: Int) {
        repository.setAppLockTimeoutSeconds(seconds)
    }

    companion object {
        /** Default pause between lock-settings re-observations
         *  after a transient read error (see init): long enough
         *  that a persistently failing store is retried gently,
         *  short enough that a recovered store is noticed promptly
         *  on the recovery screen. */
        const val REOBSERVE_DELAY_MILLIS = 1_000L
    }
}
