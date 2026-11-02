package app.nota.data.security

import app.nota.data.preferences.PreferencesRepository
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Phase 2.9 (J1): the persistence half of credential enrolment —
 * [AppLockManager.setCredential] stores exactly one verifier per
 * method, and that verifier accepts the enrolled secret and rejects
 * any other through the same check Settings re-authorisation uses.
 * (The enter → confirm half is pinned by CredentialSetupFlowTest;
 * the biometric store rides along on a raw-key provider because
 * auth-required Keystore keys cannot run under Robolectric — the
 * manager paths exercised here never touch it.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockManagerSetupTest {

    /** Raw-key provider: plain AES-256-GCM ciphers, no auth gate. */
    private class RawKeyProvider : AppLockDeviceKeyProvider {
        private val key = ByteArray(32) { (0x41 + it).toByte() }
        override fun keyAlias(): String = "rawkey-test"
        override fun sealCipherResult(): StepResult<Cipher> = StepResult.Ok(
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            },
        )

        override fun openCipherResult(packedBase64: String): StepResult<Cipher> {
            val packed = Base64.getDecoder().decode(packedBase64)
            val iv = packed.copyOfRange(0, 12)
            return StepResult.Ok(
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                },
            )
        }
    }

    private lateinit var repository: PreferencesRepository
    private lateinit var manager: AppLockManager

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        repository = PreferencesRepository(context)
        runBlocking { repository.clearAppLock() }
        manager = AppLockManager(
            repository,
            AppLockBiometricStore(repository, RawKeyProvider()),
        )
        awaitSettings { !it.isEnabled }
    }

    /** Pumps the main looper until the manager's collected settings match. */
    private fun awaitSettings(predicate: (AppLockSettings) -> Boolean) {
        repeat(200) {
            ShadowLooper.idleMainLooper()
            if (predicate(manager.settings.value)) return
            Thread.sleep(10)
        }
        ShadowLooper.idleMainLooper()
        assertTrue("settings never matched", predicate(manager.settings.value))
    }

    @Test
    fun setPasswordThenVerifyAcceptsEnrolledAndRejectsOther() = runBlocking {
        manager.setCredential(
            AppLockMethod.PASSWORD,
            AppLockCrypto.canonicalPassword("correct horse"),
        )
        awaitSettings { AppLockMethod.PASSWORD in it.enabledMethods }
        assertTrue(
            manager.verifyForSettings(
                AppLockMethod.PASSWORD,
                AppLockCrypto.canonicalPassword("correct horse"),
            ),
        )
        assertFalse(
            manager.verifyForSettings(
                AppLockMethod.PASSWORD,
                AppLockCrypto.canonicalPassword("correct house"),
            ),
        )
    }

    @Test
    fun setPinThenVerifyAcceptsEnrolledAndRejectsOther() = runBlocking {
        manager.setCredential(AppLockMethod.PIN, AppLockCrypto.canonicalPin("246810"))
        awaitSettings { AppLockMethod.PIN in it.enabledMethods }
        assertTrue(manager.verifyForSettings(AppLockMethod.PIN, AppLockCrypto.canonicalPin("246810")))
        assertFalse(manager.verifyForSettings(AppLockMethod.PIN, AppLockCrypto.canonicalPin("246811")))
    }

    @Test
    fun setPatternThenVerifyAcceptsEnrolledAndRejectsOther() = runBlocking {
        val points = listOf(0, 1, 2, 5, 8)
        manager.setCredential(AppLockMethod.PATTERN, AppLockCrypto.canonicalPattern(points))
        awaitSettings { AppLockMethod.PATTERN in it.enabledMethods }
        assertTrue(
            manager.verifyForSettings(AppLockMethod.PATTERN, AppLockCrypto.canonicalPattern(points)),
        )
        assertFalse(
            manager.verifyForSettings(
                AppLockMethod.PATTERN,
                AppLockCrypto.canonicalPattern(listOf(0, 1, 2, 5, 7)),
            ),
        )
    }

    @Test
    fun changingCredentialReplacesTheVerifier() = runBlocking {
        manager.setCredential(AppLockMethod.PIN, AppLockCrypto.canonicalPin("111111"))
        awaitSettings { AppLockMethod.PIN in it.enabledMethods }
        val firstHash = manager.settings.value.credentialFor(AppLockMethod.PIN)?.hash
        manager.setCredential(AppLockMethod.PIN, AppLockCrypto.canonicalPin("222222"))
        // Wait until the collected settings actually carry the
        // replacement verifier (fresh salt ⇒ fresh hash), so the
        // verifies below read the new slot, not the stale snapshot.
        awaitSettings {
            val h = it.credentialFor(AppLockMethod.PIN)?.hash
            h != null && h != firstHash
        }
        // The replacement lands in the same slot: the new secret
        // verifies, the old one no longer does.
        assertTrue(manager.verifyForSettings(AppLockMethod.PIN, AppLockCrypto.canonicalPin("222222")))
        assertFalse(manager.verifyForSettings(AppLockMethod.PIN, AppLockCrypto.canonicalPin("111111")))
    }
}
