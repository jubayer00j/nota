package app.nota.data.security

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.nota.data.preferences.PreferencesRepository
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

    /**
     * An in-memory store whose reads fail with an [IOException]
     * until [serving] is flipped (or forever, if it never is),
     * while writes always apply for real — the exact asymmetry of
     * the transient-error recovery scenario: the App Lock flow's
     * read errored and completed, yet the set-up-again write the
     * recovery screen offers still lands.
     */
    private class GatedPreferencesStore(
        initial: Preferences,
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        val serving = AtomicBoolean(false)
        val readCollections = AtomicInteger(0)

        override val data: Flow<Preferences> = flow {
            readCollections.incrementAndGet()
            if (!serving.get()) throw IOException("simulated transient read failure")
            emitAll(state)
        }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    /** Pumps the main looper, advancing its virtual clock (so the
     *  manager's timed re-observation also runs), until [predicate]
     *  holds for [target]. */
    private fun awaitManager(
        target: AppLockManager,
        predicate: (AppLockManager) -> Boolean,
    ) {
        repeat(200) {
            ShadowLooper.shadowMainLooper().idleFor(50, TimeUnit.MILLISECONDS)
            if (predicate(target)) return
            Thread.sleep(5)
        }
        ShadowLooper.shadowMainLooper().idleFor(50, TimeUnit.MILLISECONDS)
        assertTrue("manager state never matched", predicate(target))
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

    @Test
    fun transientReadFailure_recoveryWriteObservedWithoutRestart() = runBlocking {
        // Codex review, PR #33 (Settings redesign round): the App
        // Lock flow FAILS CLOSED on a transient IOException — it
        // emits null and COMPLETES — so a manager that collects it
        // exactly once goes blind: the recovery screen's successful
        // credential write lands in the store, but nothing observes
        // it, settingsUnreadable never clears, and the app sits on
        // the locked recovery screen until process restart. The
        // manager now re-observes after a pause; this drives the
        // whole arc against ONE manager instance — no restart.
        val store = GatedPreferencesStore(emptyPreferences())
        val flakyRepository = PreferencesRepository(store)
        val flakyManager = AppLockManager(
            flakyRepository,
            AppLockBiometricStore(flakyRepository, RawKeyProvider()),
            reobserveDelayMillis = 50,
        )

        // The read error lands: fail closed — unreadable, still locked.
        awaitManager(flakyManager) { it.settingsUnreadable.value }
        assertTrue(flakyManager.isLocked.value)

        // The recovery write (the unreadable screen's set-up-again
        // save) succeeds against the store…
        flakyManager.setCredential(AppLockMethod.PIN, AppLockCrypto.canonicalPin("246810"))
        // …and the store's reads recover.
        store.serving.set(true)

        // Re-observation picks the credential up on the SAME
        // manager: unreadable clears, the PIN is in place, and the
        // posture is the locked one a cold start against this store
        // would land in — the app never flicked open on the way
        // (no "no lock configured" emission was ever served).
        awaitManager(flakyManager) {
            !it.settingsUnreadable.value &&
                AppLockMethod.PIN in it.settings.value.enabledMethods
        }
        assertTrue(flakyManager.isLocked.value)
        assertTrue(
            flakyManager.verifyForSettings(AppLockMethod.PIN, AppLockCrypto.canonicalPin("246810")),
        )
        assertFalse(
            flakyManager.verifyForSettings(AppLockMethod.PIN, AppLockCrypto.canonicalPin("246811")),
        )
        Unit
    }

    @Test
    fun persistentReadFailure_staysUnreadable_andRetriesWithoutHotLoop() {
        // The other half of the contract: if the store NEVER
        // recovers, re-observation must not clear the fail-closed
        // posture, must not unlock anything, and must retry gently
        // — a bounded trickle of re-collections, not a spin, and
        // no crash.
        val store = GatedPreferencesStore(emptyPreferences())
        val flakyRepository = PreferencesRepository(store)
        val flakyManager = AppLockManager(
            flakyRepository,
            AppLockBiometricStore(flakyRepository, RawKeyProvider()),
            reobserveDelayMillis = 50,
        )
        awaitManager(flakyManager) { it.settingsUnreadable.value }

        // ~4 virtual seconds of re-observation cycles (50 ms delay).
        repeat(80) {
            ShadowLooper.shadowMainLooper().idleFor(50, TimeUnit.MILLISECONDS)
        }
        assertTrue(flakyManager.settingsUnreadable.value)
        assertTrue(flakyManager.isLocked.value)
        assertFalse(flakyManager.settings.value.isEnabled)
        val attempts = store.readCollections.get()
        assertTrue("re-observation never retried ($attempts collections)", attempts >= 3)
        assertTrue("re-observation is spinning ($attempts collections)", attempts <= 200)
    }
}
