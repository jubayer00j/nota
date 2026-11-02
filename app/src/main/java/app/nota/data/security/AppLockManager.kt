package app.nota.data.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.nota.data.preferences.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    @Volatile
    private var backgroundedAtMillis: Long? = null

    init {
        scope.launch {
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
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    backgroundedAtMillis = System.currentTimeMillis()
                }

                override fun onStart(owner: LifecycleOwner) {
                    maybeRelock()
                }
            },
        )
    }

    private fun maybeRelock() {
        val current = _settings.value
        if (!current.isEnabled || _isLocked.value) {
            backgroundedAtMillis = null
            return
        }
        val backgroundedAt = backgroundedAtMillis ?: return
        backgroundedAtMillis = null
        val elapsed = System.currentTimeMillis() - backgroundedAt
        val timeoutMs = current.lockTimeoutSeconds.toLong() * 1_000L
        if (timeoutMs <= 0L || elapsed >= timeoutMs) {
            _isLocked.value = true
        }
    }

    /**
     * CRYPTO-BOUND biometric unlock (Phase 2.5, replacing the old
     * bare-boolean `unlockWithBiometric()`): [authorisedCipher] is the
     * cipher BiometricPrompt authorised via its CryptoObject, obtained
     * from [AppLockBiometricStore.openCipher]. The app unlocks ONLY
     * when that cipher actually decrypts the sealed token
     * ([AppLockBiometricStore.verifyToken]) — a prompt success without
     * a working crypto result never reaches here as a proof, and a
     * cipher that cannot decrypt the token changes nothing.
     * Returns true when the app is now unlocked.
     */
    fun unlockWithBiometric(authorisedCipher: javax.crypto.Cipher): Boolean {
        val current = _settings.value
        if (!current.isEnabled || !current.biometricEnabled) return false
        if (System.currentTimeMillis() < current.lockoutUntil) return false
        if (!biometricStore.verifyToken(authorisedCipher)) return false
        _isLocked.value = false
        backgroundedAtMillis = null
        scope.launch {
            attemptMutex.withLock { repository.setAppLockFailedAttempts(0, 0L) }
        }
        return true
    }

    /**
     * Crypto-bound biometric check for Settings re-authorisation:
     * same token proof as [unlockWithBiometric], but never unlocks
     * the app and never mutates the attempt counters.
     */
    fun verifyBiometricForSettings(authorisedCipher: javax.crypto.Cipher): Boolean {
        val current = _settings.value
        if (!current.isEnabled || !current.biometricEnabled) return false
        if (System.currentTimeMillis() < current.lockoutUntil) return false
        return biometricStore.verifyToken(authorisedCipher)
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
        val ok = withContext(Dispatchers.Default) {
            AppLockCrypto.verify(
                canonicalSecret,
                credential.salt,
                credential.hash,
                AppLockCrypto.iterationsFor(method),
            )
        }
        return if (ok) {
            repository.setAppLockFailedAttempts(0, 0L)
            _isLocked.value = false
            backgroundedAtMillis = null
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
        return withContext(Dispatchers.Default) {
            AppLockCrypto.verify(
                canonicalSecret,
                credential.salt,
                credential.hash,
                AppLockCrypto.iterationsFor(method),
            )
        }
    }

    // ---- Mutations (callers gate these behind re-authorisation) -------

    /** Sets (or replaces) one method's credential. First method = lock on. */
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
}
