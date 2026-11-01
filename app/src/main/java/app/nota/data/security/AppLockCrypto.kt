package app.nota.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * App Lock cryptography (Phase 2a) — a direct port of 1.x
 * `app-lock-crypto.ts`, kept pure-JVM so it is unit-testable:
 *
 * - PBKDF2-HMAC-SHA256, 256-bit output, Base64 (standard alphabet).
 *   [java.util.Base64] is used deliberately: `android.util.Base64` is a
 *   null-returning stub in JVM unit tests, while java.util.Base64 output
 *   is byte-identical to the web `btoa` 1.x used.
 * - Iteration counts are the 1.x values (OWASP-aligned): password
 *   600_000, PIN 300_000, pattern 100_000.
 * - Canonical secret forms are the exact 1.x strings, so the scheme is
 *   auditable against the reference implementation.
 *
 * Parity note: PBKDF2 input encoding matches WebCrypto (UTF-8) for ASCII
 * secrets. App Lock credentials are freshly set up in Nota (never
 * imported from 1.x), so this is a scheme-parity port, not a data
 * migration.
 */
object AppLockCrypto {

    const val ITERATIONS_PASSWORD = 600_000
    const val ITERATIONS_PIN = 300_000
    const val ITERATIONS_PATTERN = 100_000

    const val MAX_LOCKOUT_MS = 1_800_000L // 30 minutes (1.x MAX_LOCKOUT_MS)

    fun iterationsFor(method: AppLockMethod): Int = when (method) {
        AppLockMethod.PASSWORD -> ITERATIONS_PASSWORD
        AppLockMethod.PIN -> ITERATIONS_PIN
        AppLockMethod.PATTERN -> ITERATIONS_PATTERN
    }

    /** Canonical secret forms — exact 1.x strings. Password is raw. */
    fun canonicalPassword(raw: String): String = raw

    fun canonicalPin(digits: String): String = "noma_pin_v1:$digits"

    fun canonicalPattern(points: List<Int>): String =
        "noma_pattern_v1:" + points.joinToString("-")

    /** 16 random bytes, Base64 — the 1.x salt shape. */
    fun generateSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun deriveKeyHash(secret: String, saltBase64: String, iterations: Int): String {
        require(iterations > 0) { "iterations must be positive" }
        val salt = Base64.getDecoder().decode(saltBase64)
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, 256)
        try {
            val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            return Base64.getEncoder().encodeToString(derived)
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time verification on the decoded hash bytes. */
    fun verify(
        secret: String,
        saltBase64: String,
        expectedHashBase64: String,
        iterations: Int,
    ): Boolean {
        val actual = Base64.getDecoder().decode(deriveKeyHash(secret, saltBase64, iterations))
        val expected = try {
            Base64.getDecoder().decode(expectedHashBase64)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return MessageDigest.isEqual(actual, expected)
    }

    /**
     * Exact 1.x `getLockoutDurationMs`: no lockout below 5 failures, then
     * 30s doubling per failure (30s, 60s, 120s, …), capped at 30 minutes.
     * The shift is clamped before the multiply so a huge attempt count
     * can never overflow into a negative duration.
     */
    fun lockoutDurationMs(failedAttempts: Int): Long {
        if (failedAttempts < 5) return 0L
        val exponent = failedAttempts - 5
        if (exponent >= 16) return MAX_LOCKOUT_MS // 30_000 * 2^16 already exceeds the cap
        return minOf(30_000L * (1L shl exponent), MAX_LOCKOUT_MS)
    }
}
