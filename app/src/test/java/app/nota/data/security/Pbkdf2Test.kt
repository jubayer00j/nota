package app.nota.data.security

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Pbkdf2] proof suite (Phase 2.6, Fix B).
 *
 * The manual HMAC loop replaced the `SecretKeyFactory` PBKDF2 path
 * for SPEED (the factory's provider implementation is pathologically
 * slow on Android 8.1); it must never change a single derived byte,
 * because verifiers / note-lock slots / backup headers written by
 * Nota ≤ 0.3.1 and by 1.x (WebCrypto) all have to keep verifying.
 *
 * - [equivalence_randomisedInputs] compares the loop against the
 *   legacy factory implementation (kept here, test-side only) over
 *   randomised salts, secrets and iteration counts.
 * - [equivalence_flowsThroughAppLockCrypto] pins the public entry
 *   point every consumer actually calls.
 * - [benchmark_legacyVsManual_printsTimings] prints old-vs-new
 *   wall times at the pattern iteration count (100k). These are
 *   JVM/CI PROXY numbers — the device gap on the J7's Android 8.1
 *   provider is larger; treat the printed ratio as a floor, not a
 *   device measurement.
 */
class Pbkdf2Test {

    /** The pre-2.6 implementation, verbatim, as the reference. */
    private fun legacyFactoryDerive(secret: String, salt: ByteArray, iterations: Int, keyBits: Int = 256): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, keyBits)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private val random = SecureRandom()

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    @Test
    fun equivalence_randomisedInputs() {
        val secrets = listOf(
            "correct horse battery staple",
            "noma_pin_v1:123456",
            "noma_pattern_v1:0-1-2-5-8",
            "a", // shortest realistic secret
            "x".repeat(100), // longer than the 64-byte HMAC block: key gets hashed first
            "P@ssw0rd!#$%^&*()",
        )
        val iterationCounts = listOf(1, 2, 3, 17, 250, 1_000)
        for (secret in secrets) {
            for (iterations in iterationCounts) {
                val salt = randomBytes(16)
                val expected = legacyFactoryDerive(secret, salt, iterations)
                val actual = Pbkdf2.derive(secret.toByteArray(Charsets.UTF_8), salt, iterations, 32)
                assertArrayEquals(
                    "mismatch for secret='$secret' iterations=$iterations",
                    expected,
                    actual,
                )
            }
        }
        // Random salts of several sizes + a 64-byte output for the
        // multi-block path (Nota always asks for 32; the loop is
        // written generally and should be right generally).
        repeat(12) {
            val salt = randomBytes(8 + random.nextInt(25))
            val iterations = 1 + random.nextInt(500)
            val secret = "s${random.nextLong()}"
            assertArrayEquals(
                legacyFactoryDerive(secret, salt, iterations),
                Pbkdf2.derive(secret.toByteArray(Charsets.UTF_8), salt, iterations, 32),
            )
            assertArrayEquals(
                legacyFactoryDerive(secret, salt, iterations, 512),
                Pbkdf2.derive(secret.toByteArray(Charsets.UTF_8), salt, iterations, 64),
            )
        }
    }

    @Test
    fun equivalence_unicodeSecret_utf8() {
        // The encoding contract is UTF-8 (WebCrypto). Pinned against
        // an independently computed Python hashlib vector:
        // pbkdf2_hmac('sha256', 'pässwörd-🔑'.encode('utf-8'),
        //             b'0123456789abcdef', 4096, 32)
        val salt = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        val derived = Pbkdf2.derive(
            "pässwörd-🔑".toByteArray(Charsets.UTF_8),
            salt,
            4_096,
            32,
        )
        assertEquals(
            "gYOatGQjwBGI0drNY5MABmDnt+SCb9vZz5moW+6Fu+I=",
            Base64.getEncoder().encodeToString(derived),
        )
    }

    @Test
    fun equivalence_flowsThroughAppLockCrypto() {
        // The three real 1.x iteration counts via the public API all
        // consumers share, against the legacy factory.
        val salt = randomBytes(16)
        val saltB64 = Base64.getEncoder().encodeToString(salt)
        for ((secret, iterations) in listOf(
            "correct horse battery staple" to AppLockCrypto.ITERATIONS_PASSWORD,
            AppLockCrypto.canonicalPin("987654") to AppLockCrypto.ITERATIONS_PIN,
            AppLockCrypto.canonicalPattern(listOf(0, 3, 4, 5, 7)) to AppLockCrypto.ITERATIONS_PATTERN,
        )) {
            // 600k through the legacy factory is slow even on a CI
            // JVM; the KATs in AppLockCryptoTest already pin the real
            // counts byte-for-byte. Here the factory cross-check runs
            // at a reduced count through BOTH paths' shared plumbing.
            val reduced = 5_000
            assertEquals(
                Base64.getEncoder().encodeToString(legacyFactoryDerive(secret, salt, reduced)),
                AppLockCrypto.deriveKeyHash(secret, saltB64, reduced),
            )
        }
    }

    @Test
    fun benchmark_legacyVsManual_printsTimings() {
        val iterations = AppLockCrypto.ITERATIONS_PATTERN // 100k — the pattern unlock Jubayer feels
        val secret = AppLockCrypto.canonicalPattern(listOf(0, 1, 2, 5, 8))
        val salt = randomBytes(16)

        fun medianMs(block: () -> ByteArray): Pair<Long, ByteArray> {
            block() // warmup
            val runs = mutableListOf<Long>()
            var out = ByteArray(0)
            repeat(5) {
                val started = System.nanoTime()
                out = block()
                runs.add((System.nanoTime() - started) / 1_000_000)
            }
            return runs.sorted()[runs.size / 2] to out
        }

        val (legacyMs, legacyOut) = medianMs { legacyFactoryDerive(secret, salt, iterations) }
        val (manualMs, manualOut) = medianMs {
            Pbkdf2.derive(secret.toByteArray(Charsets.UTF_8), salt, iterations, 32)
        }
        assertArrayEquals(legacyOut, manualOut)
        println(
            "PBKDF2 BENCHMARK (JVM proxy, $iterations iterations, median of 5): " +
                "legacy SecretKeyFactory = ${legacyMs}ms, manual HmacSHA256 loop = ${manualMs}ms",
        )
        // The manual loop must never be the slower path by a wide
        // margin on any provider; on the J7's Android 8.1 provider
        // the factory is the pathological one (see Pbkdf2 docs).
        assertTrue(
            "manual loop unexpectedly slow: ${manualMs}ms vs legacy ${legacyMs}ms",
            manualMs <= legacyMs * 2 + 50,
        )
    }
}
