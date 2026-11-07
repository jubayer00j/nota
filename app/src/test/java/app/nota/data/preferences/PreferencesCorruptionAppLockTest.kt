package app.nota.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.toMutablePreferences
import androidx.datastore.preferences.core.toPreferences
import app.nota.data.security.AppLockCredential
import app.nota.data.security.AppLockMethod
import java.io.File
import java.nio.file.Files
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

/**
 * Phase 6 polish, fix 1 (documented residual (a)): a CORRUPTED
 * preferences file must never silently clear App Lock.
 *
 * The chain under test, end to end:
 * 1. A corrupt store file is repaired by the PRODUCTION corruption
 *    handler ([notaStoreCorruptionHandler] — the same instance the
 *    app delegate installs), and the replacement carries the
 *    repaired marker ([PreferencesRepository.KEY_STORE_REPAIRED]).
 * 2. The production App Lock reading ([appLockSettingsOrNull] — the
 *    exact mapping [PreferencesRepository.appLockSettings] applies)
 *    reads a marked snapshot as null: "unknown", the fail-closed
 *    signal the AppLockManager turns into settingsUnreadable
 *    (stays locked, set up again) — never as an empty settings
 *    object, which would read as App Lock OFF.
 * 3. Recovery: on the real app store, setting up a credential again
 *    (the write the unreadable screen offers) reads back normally.
 *
 * Why a scratch factory store for links 1–2 instead of corrupting
 * the app store's own file: the app store is a process-wide
 * singleton shared across this suite's Robolectric classes
 * (AppLockManagerSetupTest drives the real store too and clears
 * state first for the same reason) — once any class has activated
 * it, bytes written underneath it are never re-read, so a
 * file-level corruption there is not hermetic. The handler and the
 * mapping are production code either way; only the file is scratch.
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesCorruptionAppLockTest {

    @Test
    fun corruptionRepair_installsMarker_andAppLockFailsClosed() = runBlocking {
        val dir = Files.createTempDirectory("nota-corrupt-store").toFile()
        val file = File(dir, "nota_preferences.preferences_pb")
        // 0xFF bytes are not a valid preferences proto (invalid wire
        // type), so the first read triggers the corruption handler.
        file.writeBytes(ByteArray(64) { 0xFF.toByte() })

        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = notaStoreCorruptionHandler(),
            produceFile = { file },
        )
        val repaired = store.data.first()

        // Link 1: the repair installed the marker (and no credential
        // material — whatever the damaged file held is gone).
        assertTrue(repaired[PreferencesRepository.KEY_STORE_REPAIRED] == true)
        assertNull(appLockSettingsOrNull(repaired)?.pin)

        // Link 2: the App Lock reading of a repaired snapshot is
        // null (fail closed), exactly like the IO-error posture —
        // NOT an empty/off settings object. The settings factory
        // itself also carries the marker.
        assertNull(appLockSettingsOrNull(repaired))
        assertNull(appLockSettingsOrNull(null))
        assertTrue(
            repairedStorePreferences()[PreferencesRepository.KEY_STORE_REPAIRED] == true,
        )

        // Sanity: the same mapping reads a HEALTHY snapshot normally
        // (the rule fires on the marker, not on everything).
        val healthy = emptyPreferences().toMutablePreferences().apply {
            this[PreferencesRepository.KEY_LOCK_PIN_SALT] = "c2FsdA=="
            this[PreferencesRepository.KEY_LOCK_PIN_HASH] = "aGFzaA=="
        }.toPreferences()
        val settings = appLockSettingsOrNull(healthy)
        assertNotNull(settings)
        assertEquals("c2FsdA==", settings!!.pin?.salt)
        Unit
    }

    @Test
    fun recoveryWrite_readsBackNormally() = runBlocking {
        // Link 3, on the real app store: the set-up-again write (it
        // removes the repair marker as part of the edit — see
        // PreferencesRepository.setAppLockCredential) leaves the
        // settings readable with the new credential in place.
        val repository = PreferencesRepository(RuntimeEnvironment.getApplication())
        repository.setAppLockCredential(
            AppLockMethod.PIN,
            AppLockCredential(salt = "c2FsdA==", hash = "aGFzaA=="),
        )
        val recovered = repository.appLockSettings.first()
        assertNotNull(recovered)
        assertEquals("aGFzaA==", recovered!!.pin?.hash)
        Unit
    }
}
