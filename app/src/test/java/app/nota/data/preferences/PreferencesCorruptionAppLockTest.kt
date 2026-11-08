package app.nota.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.nota.data.security.AppLockCredential
import app.nota.data.security.AppLockMethod
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * 4. The recovery write's failure contract (Codex review, PR #33):
 *    if storage rejects that write, the failure must surface — the
 *    write used to be swallowed by safeEdit, so the recovery form
 *    closed as if the credential had saved while nothing persisted.
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

    /**
     * An in-memory store for the write-contract tests: reads serve
     * the seeded snapshot; writes either apply for real or fail with
     * an [IOException], as if the device storage rejected them.
     */
    private class InMemoryPreferencesStore(
        initial: Preferences,
        private val failWrites: Boolean,
    ) : DataStore<Preferences> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<Preferences> = state

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            if (failWrites) throw IOException("simulated storage failure")
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    @Test
    fun credentialWrite_whenStoreRejectsWrites_throwsAndStateIsUnchanged() = runBlocking {
        // The recovery scenario, one link further (Codex review,
        // PR #33): the store was corruption-repaired (marker set,
        // credential lost) and storage now rejects the
        // set-up-again write. The write must REPORT the failure —
        // it used to go through safeEdit, which swallowed the
        // IOException, so the recovery form closed as if the
        // credential had saved while nothing persisted. The seam
        // under test is the exact write PreferencesRepository
        // .setAppLockCredential delegates to (and AppLockManager
        // .setCredential calls without catching, so the failure
        // reaches the form's existing error handling).
        val store = InMemoryPreferencesStore(
            repairedStorePreferences(),
            failWrites = true,
        )
        val failure = try {
            writeAppLockCredential(
                store,
                AppLockMethod.PIN,
                AppLockCredential(salt = "c2FsdA==", hash = "aGFzaA=="),
            )
            null
        } catch (expected: IOException) {
            expected
        }
        assertNotNull("the failed lock write must surface, not be swallowed", failure)

        // Nothing moved: the repair marker is still set, no
        // credential landed, and the App Lock reading of the store
        // is still the fail-closed null.
        val after = store.data.first()
        assertTrue(after[PreferencesRepository.KEY_STORE_REPAIRED] == true)
        assertNull(after[PreferencesRepository.KEY_LOCK_PIN_SALT])
        assertNull(appLockSettingsOrNull(after))
        Unit
    }

    @Test
    fun credentialWrite_onRepairedStore_clearsMarkerAndLands() = runBlocking {
        // The same write against a store that accepts it: the
        // credential lands and the repair marker is removed, so
        // the App Lock reading returns to normal with the new
        // credential in place.
        val store = InMemoryPreferencesStore(
            repairedStorePreferences(),
            failWrites = false,
        )
        writeAppLockCredential(
            store,
            AppLockMethod.PIN,
            AppLockCredential(salt = "c2FsdA==", hash = "aGFzaA=="),
        )
        val after = store.data.first()
        assertNull(after[PreferencesRepository.KEY_STORE_REPAIRED])
        val settings = appLockSettingsOrNull(after)
        assertNotNull(settings)
        assertEquals("aGFzaA==", settings!!.pin?.hash)
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
