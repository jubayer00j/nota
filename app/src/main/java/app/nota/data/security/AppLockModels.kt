package app.nota.data.security

/**
 * App Lock credential methods (Phase 2a — 1.x parity). Fingerprint is NOT
 * a method: it is a convenience gate on top of one of these, exactly like
 * 1.x, and can never exist on its own.
 */
enum class AppLockMethod(val label: String) {
    PASSWORD("Password"),
    PIN("PIN"),
    PATTERN("Pattern"),
}

/** One method's stored verifier: a random salt + PBKDF2 hash (Base64). */
data class AppLockCredential(val salt: String, val hash: String)

/**
 * Persisted App Lock state (DataStore, see PreferencesRepository).
 * App Lock is ENABLED iff at least one credential slot is present — there
 * is no separate enabled flag that could disagree with the slots.
 * Credential hashes are device-local and freshly set up in Nota; nothing
 * is imported from 1.x.
 */
data class AppLockSettings(
    val password: AppLockCredential? = null,
    val pin: AppLockCredential? = null,
    val pattern: AppLockCredential? = null,
    val biometricEnabled: Boolean = false,
    val failedAttempts: Int = 0,
    /** Epoch millis until which unlock attempts are refused; 0 = none. */
    val lockoutUntil: Long = 0L,
    /** Seconds before re-locking after backgrounding; 0 = immediately. */
    val lockTimeoutSeconds: Int = 0,
) {
    fun credentialFor(method: AppLockMethod): AppLockCredential? = when (method) {
        AppLockMethod.PASSWORD -> password
        AppLockMethod.PIN -> pin
        AppLockMethod.PATTERN -> pattern
    }

    val enabledMethods: Set<AppLockMethod>
        get() = AppLockMethod.entries.filter { credentialFor(it) != null }.toSet()

    val isEnabled: Boolean get() = enabledMethods.isNotEmpty()

    companion object {
        /** The exact 1.x timeout option set, in seconds. */
        val TIMEOUT_OPTIONS: List<Pair<Int, String>> = listOf(
            0 to "Immediately",
            10 to "10 seconds",
            60 to "1 minute",
            300 to "5 minutes",
            900 to "15 minutes",
            1800 to "30 minutes",
            3600 to "1 hour",
        )
    }
}
