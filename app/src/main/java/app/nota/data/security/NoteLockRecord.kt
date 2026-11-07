package app.nota.data.security

import org.json.JSONObject

/**
 * Per-note lock methods (Phase 2b) — the 1.x `NoteLockMethod` set. The
 * [wireName] is the slot key used inside the lock record JSON and must
 * stay byte-identical to 1.x. [label] reproduces 1.x's
 * NOTE_LOCK_METHOD_LABEL exactly (including the lowercase "pattern"),
 * because it appears verbatim in user-facing error strings.
 */
enum class NoteLockMethod(val wireName: String, val label: String) {
    PASSWORD("password", "Password"),
    PIN("pin", "PIN"),
    PATTERN("pattern", "pattern"),
    ;

    /** The App Lock method whose shared credential UI drives this one. */
    val appLockMethod: AppLockMethod
        get() = when (this) {
            PASSWORD -> AppLockMethod.PASSWORD
            PIN -> AppLockMethod.PIN
            PATTERN -> AppLockMethod.PATTERN
        }

    companion object {
        fun fromWireName(name: String): NoteLockMethod? =
            entries.firstOrNull { it.wireName == name }

        fun fromAppLockMethod(method: AppLockMethod): NoteLockMethod = when (method) {
            AppLockMethod.PASSWORD -> PASSWORD
            AppLockMethod.PIN -> PIN
            AppLockMethod.PATTERN -> PATTERN
        }
    }
}

/** One wrapped copy of the note encryption key, sealed by a single
 *  credential (1.x `NoteLockSlot`). */
data class NoteLockSlot(
    /** Base64 PBKDF2 salt for this slot's credential. */
    val salt: String,
    /** PBKDF2-HMAC-SHA256 iterations used to derive this slot's key. */
    val iterations: Int,
    /** Base64 (IV || AES-256-GCM ciphertext) of the note encryption key. */
    val wrappedKey: String,
)

/**
 * Per-note lock record (Phase 2b) — the 1.x `NoteLock` shape, stored
 * VERBATIM as JSON in `notes.lockRecordJson`:
 *
 * `{"v":1,"slots":{"password":{"salt":…,"iterations":…,"wrappedKey":…}},
 *   "biometric":false,"failedAttempts":0,"lockoutUntil":null,"lockedAt":<millis>}`
 *
 * Serialisation builds the string deliberately so field names AND field
 * order match what 1.x's JSON.stringify produced (v, slots, biometric,
 * failedAttempts, lockoutUntil, lockedAt; salt, iterations, wrappedKey
 * inside a slot). Parsing is tolerant (org.json): missing fields fall
 * back to the 1.x defaults, unknown fields are ignored, and a
 * `lockoutUntil` that is absent reads the same as an explicit null — a
 * real 1.x record must always parse.
 */
data class NoteLockRecord(
    val v: Int = 1,
    val slots: Map<NoteLockMethod, NoteLockSlot> = emptyMap(),
    val biometric: Boolean = false,
    val failedAttempts: Int = 0,
    val lockoutUntil: Long? = null,
    val lockedAt: Long = 0L,
) {
    /** Seconds left in the lockout window (0 when not locked out) — 1.x `noteLockoutSeconds`. */
    fun lockoutSeconds(now: Long = System.currentTimeMillis()): Long {
        val until = lockoutUntil ?: return 0L
        if (until <= now) return 0L
        return (until - now + 999L) / 1000L
    }

    /**
     * 1.x `recordFailedAttempt`, pure form: the attempt count increments
     * and the lockout deadline is overwritten ONLY when the new lockout
     * duration is > 0 (below the 5-failure threshold the previous
     * deadline — normally null — is preserved untouched).
     * Returns the new record + the lockout seconds just imposed.
     */
    fun withFailedAttempt(now: Long = System.currentTimeMillis()): Pair<NoteLockRecord, Long> {
        val attempts = failedAttempts + 1
        val durationMs = AppLockCrypto.lockoutDurationMs(attempts)
        val next = copy(
            failedAttempts = attempts,
            lockoutUntil = if (durationMs > 0) now + durationMs else lockoutUntil,
        )
        return next to (durationMs + 999L) / 1000L
    }

    /** 1.x `resetAttempts`: zeroes the counter and clears the deadline. */
    fun withResetAttempts(): NoteLockRecord =
        if (failedAttempts == 0 && lockoutUntil == null) this
        else copy(failedAttempts = 0, lockoutUntil = null)

    fun toJson(): String = buildString {
        append("{\"v\":").append(v)
        append(",\"slots\":{")
        var first = true
        for ((method, slot) in slots) {
            if (!first) append(',')
            first = false
            append('"').append(method.wireName).append("\":{\"salt\":\"")
                .append(jsonEscape(slot.salt))
                .append("\",\"iterations\":").append(slot.iterations)
                .append(",\"wrappedKey\":\"").append(jsonEscape(slot.wrappedKey))
                .append("\"}")
        }
        append("},\"biometric\":").append(biometric)
        append(",\"failedAttempts\":").append(failedAttempts)
        append(",\"lockoutUntil\":").append(lockoutUntil?.toString() ?: "null")
        append(",\"lockedAt\":").append(lockedAt)
        append('}')
    }

    companion object {
        /** The most PBKDF2 iterations a stored slot may name — the
         *  same ceiling [app.nota.data.backup.BackupCrypto] applies
         *  to the backup header (Phase 6 polish). Legitimate records
         *  use 100k–600k, far below this. */
        const val MAX_PBKDF2_ITERATIONS = 2_000_000

        /** Parses a stored record; null when the text is not a JSON object. */
        fun parse(json: String?): NoteLockRecord? {
            if (json.isNullOrBlank()) return null
            val root = try {
                JSONObject(json)
            } catch (e: Exception) {
                return null
            }
            val slots = LinkedHashMap<NoteLockMethod, NoteLockSlot>()
            val slotsObj = root.optJSONObject("slots")
            if (slotsObj != null) {
                for (method in NoteLockMethod.entries) {
                    val slotObj = slotsObj.optJSONObject(method.wireName) ?: continue
                    val salt = slotObj.optString("salt", "")
                    val iterations = slotObj.optInt("iterations", 0)
                    val wrappedKey = slotObj.optString("wrappedKey", "")
                    // Phase 6 polish: the iteration count is CAPPED,
                    // exactly as BackupCrypto caps the backup header.
                    // A corrupt or crafted record (e.g. arriving via a
                    // hostile backup) naming billions of iterations
                    // would otherwise make an unlock attempt run
                    // PBKDF2 effectively forever; an over-cap slot is
                    // rejected here, at parse, like any invalid slot.
                    if (salt.isNotEmpty() &&
                        iterations in 1..MAX_PBKDF2_ITERATIONS &&
                        wrappedKey.isNotEmpty()
                    ) {
                        slots[method] = NoteLockSlot(salt, iterations, wrappedKey)
                    }
                }
            }
            val lockoutUntil: Long? =
                if (!root.has("lockoutUntil") || root.isNull("lockoutUntil")) {
                    null
                } else {
                    root.optLong("lockoutUntil", 0L).takeIf { it > 0L }
                }
            return NoteLockRecord(
                v = root.optInt("v", 1),
                slots = slots,
                biometric = root.optBoolean("biometric", false),
                failedAttempts = root.optInt("failedAttempts", 0),
                lockoutUntil = lockoutUntil,
                lockedAt = root.optLong("lockedAt", 0L),
            )
        }

        private fun jsonEscape(value: String): String = buildString {
            for (c in value) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
                }
            }
        }
    }
}
