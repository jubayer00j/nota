package app.nota.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The single preferences DataStore delegate for the app. It must live at
 * top level and be instantiated once — multiple DataStore instances for
 * the same file throw at runtime.
 */
private val Context.notaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nota_preferences",
    // A corrupt file is replaced with empty preferences (defaults)
    // instead of failing every read AND write: without a handler,
    // dataStore.edit throws CorruptionException on a corrupt file,
    // so the read-side catch in PreferencesRepository could never
    // actually recover via a Settings write.
    corruptionHandler = ReplaceFileCorruptionHandler(
        produceNewData = { emptyPreferences() },
    ),
)

/**
 * App preferences (Phase 1a) — the DataStore settings store spec §2 calls
 * for, starting with the two Appearance switches:
 *
 * - dynamicColour: Material You dynamic colour. Default FALSE — static
 *   pine & paper is Nota's default theme (Phase 1a decision; dynamic
 *   colour is what made the device UI diverge from the mock-ups).
 * - oledBlack: OLED true-black dark theme. Default false.
 *
 * Registered as a Koin single; observed by MainActivity and Settings.
 */
class PreferencesRepository(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.notaDataStore

    val dynamicColour: Flow<Boolean> = dataStore.safeData.map { prefs ->
        prefs[KEY_DYNAMIC_COLOUR] ?: false
    }

    val oledBlack: Flow<Boolean> = dataStore.safeData.map { prefs ->
        prefs[KEY_OLED_BLACK] ?: false
    }

    /** Phase 1b: open on the Today surface instead of Notes. Default false;
     *  read once at launch by MainActivity — changes apply on next launch. */
    val startOnToday: Flow<Boolean> = dataStore.safeData.map { prefs ->
        prefs[KEY_START_ON_TODAY] ?: false
    }

    suspend fun setDynamicColour(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_DYNAMIC_COLOUR] = enabled }
    }

    suspend fun setOledBlack(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_OLED_BLACK] = enabled }
    }

    suspend fun setStartOnToday(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_START_ON_TODAY] = enabled }
    }

    /** A corrupt/unreadable preferences file degrades to defaults, not a crash. */
    private val DataStore<Preferences>.safeData: Flow<Preferences>
        get() = data.catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }

    private companion object {
        val KEY_DYNAMIC_COLOUR = booleanPreferencesKey("dynamic_colour")
        val KEY_OLED_BLACK = booleanPreferencesKey("oled_black")
        val KEY_START_ON_TODAY = booleanPreferencesKey("start_on_today")
    }
}
