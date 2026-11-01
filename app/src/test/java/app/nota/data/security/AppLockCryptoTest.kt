package app.nota.data.security

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App Lock crypto tests (Phase 2a).
 *
 * The known-answer vectors below were computed INDEPENDENTLY of this
 * implementation with Python `hashlib.pbkdf2_hmac('sha256', secret,
 * salt, iterations, dklen=32)` and pasted in as constants — they pin the
 * exact 1.x scheme (canonical secret forms, per-method iteration counts,
 * Base64 output), not just internal consistency. Fixed salt for every
 * vector: the 16 ASCII bytes "0123456789abcdef", Base64
 * "MDEyMzQ1Njc4OWFiY2RlZg==".
 */
class AppLockCryptoTest {

    private val saltB64 = "MDEyMzQ1Njc4OWFiY2RlZg=="

    @Test
    fun kat_password_realIterationCount() {
        // pbkdf2_hmac('sha256', b'correct horse battery staple', salt, 600_000)
        assertEquals(
            "bEpkaq0Q0Get1ft52QeKFtqD1Q+BZwqOdZOySebZSTY=",
            AppLockCrypto.deriveKeyHash(
                AppLockCrypto.canonicalPassword("correct horse battery staple"),
                saltB64,
                AppLockCrypto.ITERATIONS_PASSWORD,
            ),
        )
    }

    @Test
    fun kat_pin_realIterationCount_canonicalForm() {
        // pbkdf2_hmac('sha256', b'noma_pin_v1:1234', salt, 300_000)
        assertEquals(
            "GCKMZrEu3FCLmD6HV1Wv9SGcmhcEyijXJUxlGszbSVk=",
            AppLockCrypto.deriveKeyHash(
                AppLockCrypto.canonicalPin("1234"),
                saltB64,
                AppLockCrypto.ITERATIONS_PIN,
            ),
        )
    }

    @Test
    fun kat_pattern_realIterationCount_canonicalForm() {
        // pbkdf2_hmac('sha256', b'noma_pattern_v1:0-1-2-5', salt, 100_000)
        assertEquals(
            "qZVJIwRkHyWG8Cr8wXRagP0c1HdfDanGArez8EGsHco=",
            AppLockCrypto.deriveKeyHash(
                AppLockCrypto.canonicalPattern(listOf(0, 1, 2, 5)),
                saltB64,
                AppLockCrypto.ITERATIONS_PATTERN,
            ),
        )
    }

    @Test
    fun canonicalSecretForms_areExact1xStrings() {
        assertEquals("noma_pin_v1:9876", AppLockCrypto.canonicalPin("9876"))
        assertEquals(
            "noma_pattern_v1:0-4-8-7",
            AppLockCrypto.canonicalPattern(listOf(0, 4, 8, 7)),
        )
        assertEquals("raw pass phrase", AppLockCrypto.canonicalPassword("raw pass phrase"))
    }

    @Test
    fun iterationCounts_areThe1xValues() {
        assertEquals(600_000, AppLockCrypto.iterationsFor(AppLockMethod.PASSWORD))
        assertEquals(300_000, AppLockCrypto.iterationsFor(AppLockMethod.PIN))
        assertEquals(100_000, AppLockCrypto.iterationsFor(AppLockMethod.PATTERN))
    }

    @Test
    fun verify_acceptsCorrectAndRejectsWrong_withLowIterations() {
        val iterations = 1_000 // the parameter is honoured, keeping this fast
        val hash = AppLockCrypto.deriveKeyHash("open sesame", saltB64, iterations)
        assertTrue(AppLockCrypto.verify("open sesame", saltB64, hash, iterations))
        assertFalse(AppLockCrypto.verify("open sesami", saltB64, hash, iterations))
        assertFalse(AppLockCrypto.verify("open sesame", saltB64, hash, iterations + 1))
        assertFalse(AppLockCrypto.verify("open sesame", saltB64, "not-base64!!!", iterations))
    }

    @Test
    fun lockoutTable_matches1xExactly() {
        assertEquals(0L, AppLockCrypto.lockoutDurationMs(0))
        assertEquals(0L, AppLockCrypto.lockoutDurationMs(4))
        assertEquals(30_000L, AppLockCrypto.lockoutDurationMs(5))
        assertEquals(60_000L, AppLockCrypto.lockoutDurationMs(6))
        assertEquals(120_000L, AppLockCrypto.lockoutDurationMs(7))
        assertEquals(240_000L, AppLockCrypto.lockoutDurationMs(8))
        assertEquals(1_800_000L, AppLockCrypto.lockoutDurationMs(11)) // first capped value
        assertEquals(1_800_000L, AppLockCrypto.lockoutDurationMs(100))
        assertEquals(1_800_000L, AppLockCrypto.lockoutDurationMs(Int.MAX_VALUE))
    }

    @Test
    fun generateSalt_is16RandomBytesBase64() {
        val a = AppLockCrypto.generateSalt()
        val b = AppLockCrypto.generateSalt()
        assertEquals(16, Base64.getDecoder().decode(a).size)
        assertEquals(16, Base64.getDecoder().decode(b).size)
        assertNotEquals(a, b)
    }
}
