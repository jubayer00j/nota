package app.nota.data.security

import app.nota.data.preferences.PreferencesRepository
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * App Lock biometric enable/unlock state machine (Phase 2.6, Fix A).
 *
 * In 0.3.1 every failure in this pipeline collapsed to a silent
 * null/false: enabling fingerprint on the J7 ran the re-auth, the
 * first crypto step failed inside the Keystore provider, and the
 * Settings toggle ended up off with no message anywhere. These
 * tests pin the 2.6 contract:
 *
 * - every step returns a typed [StepResult] carrying its step +
 *   platform reason (and a screenshot-able userMessage);
 * - a failed seal persists NOTHING (so the Settings toggle, which
 *   only turns on after a sealed + read-back-verified token, stays
 *   honestly off);
 * - a stale/corrupt stored token fails CLOSED (typed TOKEN_READ /
 *   DECRYPT failure), never open.
 *
 * The device key is an injected raw-key provider: auth-required
 * AndroidKeyStore keys cannot be exercised under Robolectric, so
 * the production provider's behaviour is device-verified; what is
 * pinned here is the flow logic around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockBiometricStoreTest {

    /** Raw-key provider: plain AES-256-GCM ciphers, no auth gate. */
    private class RawKeyProvider(
        private val key: ByteArray = ByteArray(32) { (0x30 + it).toByte() },
    ) : AppLockDeviceKeyProvider {
        override fun sealCipherResult(): StepResult<Cipher> = StepResult.Ok(
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            },
        )

        override fun openCipherResult(packedBase64: String): StepResult<Cipher> = try {
            val packed = Base64.getDecoder().decode(packedBase64)
            StepResult.Ok(
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        GCMParameterSpec(128, packed.copyOfRange(0, 12)),
                    )
                },
            )
        } catch (e: Exception) {
            StepResult.Failed(BiometricStep.CIPHER_INIT, "fake provider: " + e.javaClass.simpleName)
        }
    }

    /** A provider whose named step always fails (the J7 shape). */
    private class FailingProvider(private val step: BiometricStep) : AppLockDeviceKeyProvider {
        override fun sealCipherResult(): StepResult<Cipher> =
            StepResult.Failed(step, "KeyPermanentlyInvalidatedException: fake")

        override fun openCipherResult(packedBase64: String): StepResult<Cipher> =
            StepResult.Failed(step, "KeyPermanentlyInvalidatedException: fake")
    }

    private lateinit var preferences: PreferencesRepository

    @Before
    fun setUp() {
        preferences = PreferencesRepository(RuntimeEnvironment.getApplication())
    }

    private fun storeWith(provider: AppLockDeviceKeyProvider) =
        AppLockBiometricStore(preferences, provider)

    /** Runs the whole enable pipeline the way SecuritySection does. */
    private fun enable(store: AppLockBiometricStore): StepResult<Unit> = runBlocking {
        when (val cipher = store.sealCipherResult()) {
            is StepResult.Failed -> cipher
            is StepResult.Ok -> store.sealToken(cipher.value)
        }
    }

    @Test
    fun enable_success_sealsPersistsAndVerifies() = runBlocking {
        val store = storeWith(RawKeyProvider())
        store.clear()

        val result = enable(store)
        assertTrue("enable should succeed, was $result", result is StepResult.Ok)
        assertTrue(store.hasToken())
        assertTrue(store.tokenUsable())

        // Unlock direction: open + verify with the authorised cipher.
        val opened = store.openCipherResult()
        assertTrue(opened is StepResult.Ok)
        val verified = store.verifyTokenResult((opened as StepResult.Ok).value)
        assertTrue("verify should succeed, was $verified", verified is StepResult.Ok)
    }

    @Test
    fun enable_keyGenerationFailure_surfacesStepAndPersistsNothing() = runBlocking {
        val store = storeWith(FailingProvider(BiometricStep.KEY_GENERATION))
        store.clear()

        val result = enable(store)
        assertTrue(result is StepResult.Failed)
        val failed = result as StepResult.Failed
        assertEquals(BiometricStep.KEY_GENERATION, failed.step)
        assertTrue(failed.userMessage.contains("key generation"))
        assertTrue(failed.userMessage.contains("KeyPermanentlyInvalidatedException"))
        // The state machine's core promise: no token, so the toggle
        // (driven by a successful seal) can never report ON.
        assertFalse(store.hasToken())
        assertFalse(store.tokenUsable())
    }

    @Test
    fun enable_cipherInitFailure_surfacesStepAndPersistsNothing() = runBlocking {
        val store = storeWith(FailingProvider(BiometricStep.CIPHER_INIT))
        store.clear()

        val result = enable(store)
        assertTrue(result is StepResult.Failed)
        val failed = result as StepResult.Failed
        assertEquals(BiometricStep.CIPHER_INIT, failed.step)
        assertTrue(failed.userMessage.contains("cipher setup"))
        assertFalse(store.hasToken())
    }

    @Test
    fun verify_withWrongDeviceKey_failsAtDecryptStep() = runBlocking {
        val storeA = storeWith(RawKeyProvider(ByteArray(32) { (0x30 + it).toByte() }))
        storeA.clear()
        assertTrue(enable(storeA) is StepResult.Ok)

        // Same storage, different device key (key rotated/lost):
        // the cipher BUILDS, but the GCM decrypt must fail closed.
        val storeB = storeWith(RawKeyProvider(ByteArray(32) { (0x60 + it).toByte() }))
        val opened = storeB.openCipherResult()
        assertTrue(opened is StepResult.Ok)
        val verified = storeB.verifyTokenResult((opened as StepResult.Ok).value)
        assertTrue(verified is StepResult.Failed)
        assertEquals(BiometricStep.DECRYPT, (verified as StepResult.Failed).step)
    }

    @Test
    fun corruptStoredToken_failsClosedWithTokenReadReason() = runBlocking {
        val store = storeWith(RawKeyProvider())
        store.clear()

        preferences.setAppLockBiometricToken("!!!not-base64!!!")
        val notBase64 = store.openCipherResult()
        assertTrue(notBase64 is StepResult.Failed)
        assertEquals(BiometricStep.TOKEN_READ, (notBase64 as StepResult.Failed).step)
        assertFalse(store.tokenUsable())

        // Valid Base64, but far too short to hold iv + ciphertext.
        preferences.setAppLockBiometricToken(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
        val truncated = store.openCipherResult()
        assertTrue(truncated is StepResult.Failed)
        assertEquals(BiometricStep.TOKEN_READ, (truncated as StepResult.Failed).step)
        assertFalse(store.tokenUsable())
    }

    @Test
    fun openCipher_withNoToken_failsTokenRead() = runBlocking {
        val store = storeWith(RawKeyProvider())
        store.clear()

        val opened = store.openCipherResult()
        assertTrue(opened is StepResult.Failed)
        assertEquals(BiometricStep.TOKEN_READ, (opened as StepResult.Failed).step)
    }

    @Test
    fun clear_removesTheToken() = runBlocking {
        val store = storeWith(RawKeyProvider())
        assertTrue(enable(store) is StepResult.Ok)
        assertTrue(store.hasToken())

        store.clear()
        assertFalse(store.hasToken())
        assertTrue(store.openCipherResult() is StepResult.Failed)
    }
}
