package app.nota.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Writing typography preferences (Phase 6, PR #43) against a
 * scratch DataStore file — the hermetic pattern of
 * [PreferencesCorruptionAppLockTest]: defaults, round-trip,
 * persistence across repository instances, and write-side
 * clamping to the 1.x ranges (fontSize 14..22, lineHeight
 * 1.4..2.2).
 */
@RunWith(RobolectricTestRunner::class)
class PreferencesWritingTest {

    private fun scratchRepository(): Pair<PreferencesRepository, File> {
        val dir = Files.createTempDirectory("nota-writing-prefs").toFile()
        val file = File(dir, "writing.preferences_pb")
        val store = PreferenceDataStoreFactory.create(produceFile = { file })
        return PreferencesRepository(store) to file
    }

    private fun repositoryOver(file: File): PreferencesRepository =
        PreferencesRepository(
            PreferenceDataStoreFactory.create(produceFile = { file }),
        )

    @Test
    fun defaults_are17pxAnd1Point7() = runBlocking {
        val (prefs, _) = scratchRepository()
        assertEquals(17, prefs.writingFontSize.first())
        assertEquals(1.7f, prefs.writingLineHeight.first(), 0.0001f)
        assertEquals(17, prefs.writingFontSizeNow())
        assertEquals(1.7f, prefs.writingLineHeightNow(), 0.0001f)
    }

    @Test
    fun set_roundTrips_andPersistsAcrossInstances() = runBlocking {
        val (prefs, file) = scratchRepository()
        prefs.setWritingFontSize(20)
        prefs.setWritingLineHeight(2.0f)
        assertEquals(20, prefs.writingFontSizeNow())
        assertEquals(2.0f, prefs.writingLineHeightNow(), 0.0001f)

        // A fresh repository over the same store file reads the
        // same values back — the settings survive a restart.
        val fresh = repositoryOver(file)
        assertEquals(20, fresh.writingFontSizeNow())
        assertEquals(2.0f, fresh.writingLineHeightNow(), 0.0001f)
    }

    @Test
    fun setters_clampToThe1xRanges() = runBlocking {
        val (prefs, _) = scratchRepository()
        prefs.setWritingFontSize(99)
        assertEquals(22, prefs.writingFontSizeNow())
        prefs.setWritingFontSize(1)
        assertEquals(14, prefs.writingFontSizeNow())
        prefs.setWritingLineHeight(9f)
        assertEquals(2.2f, prefs.writingLineHeightNow(), 0.0001f)
        prefs.setWritingLineHeight(0.5f)
        assertEquals(1.4f, prefs.writingLineHeightNow(), 0.0001f)
    }
}
