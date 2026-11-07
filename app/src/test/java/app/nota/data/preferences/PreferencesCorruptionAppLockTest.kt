package app.nota.data.preferences

import app.nota.data.security.AppLockCredential
import app.nota.data.security.AppLockMethod
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 6 polish, fix 1 (documented residual (a)): a CORRUPTED
 * preferences file must never silently clear App Lock.
 *
 * DataStore repairs corruption by replacing the file; before this
 * fix the replacement was bare-empty preferences, indistinguishable
 * from a fresh never-locked install, so the AppLockManager flow
 * read "no credentials" = App Lock OFF and the app opened for
 * whoever held the phone. The repair now installs
 * [repairedStorePreferences] carrying the repaired marker, and
 * [PreferencesRepository.appLockSettings] maps a marked store to
 * null — the SAME fail-closed signal an IO error produces, which
 * the manager surfaces as settingsUnreadable (locked, with a
 * set-up-again path) instead of "off".
 *
 * @Config sdk 34 is deliberate and unique in the suite: Robolectric
 * keys its sandbox (classloader + filesystem) on the config, so
 * this class gets a pristine filesDir where the store file can be
 * corrupted BEFORE anything reads it — no other test class can
 * have created or cached the store first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesCorruptionAppLockTest {

    @Test
    fun corruptedStore_appLockFailsClosed_thenSetupRecovers() = runBlocking {
        val context = RuntimeEnvironment.getApplication()

        // Corrupt the store file before the DataStore is ever read:
        // 0xFF bytes are not a valid preferences proto (invalid wire
        // type), so the first read triggers the corruption handler.
        val storeFile = File(
            File(context.filesDir, "datastore"),
            "nota_preferences.preferences_pb",
        )
        storeFile.parentFile?.mkdirs()
        storeFile.writeBytes(ByteArray(64) { 0xFF.toByte() })

        val repository = PreferencesRepository(context)

        // The repair marker is what the handler installs.
        assertTrue(repairedStorePreferences()[PreferencesRepository.KEY_STORE_REPAIRED] == true)

        // FAIL CLOSED: the settings read as null (unreadable), NOT as
        // an enabled=false settings object — null is the signal the
        // AppLockManager turns into "stays locked, set up again".
        assertNull(repository.appLockSettings.first())

        // Recovery: setting up App Lock again from that honest state
        // writes a real credential and clears the marker, so the
        // settings read normally afterwards.
        repository.setAppLockCredential(
            AppLockMethod.PIN,
            AppLockCredential(salt = "c2FsdA==", hash = "aGFzaA=="),
        )
        val recovered = repository.appLockSettings.first()
        assertNotNull(recovered)
        assertEquals("c2FsdA==", recovered!!.pin?.salt)
        assertEquals("aGFzaA==", recovered.pin?.hash)
        Unit
    }
}
