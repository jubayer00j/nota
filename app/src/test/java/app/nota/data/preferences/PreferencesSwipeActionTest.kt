package app.nota.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3.5 (D3): swipe-action persistence. Defaults are the pre-3.5
 * behaviour (right = Archive, left = Trash); writes round-trip
 * through the real DataStore and survive a fresh repository instance
 * over the same store.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreferencesSwipeActionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaults_areArchiveRightTrashLeft() = runBlocking {
        val prefs = PreferencesRepository(context)
        assertEquals(SwipeAction.ARCHIVE, prefs.swipeRightActionNow())
        assertEquals(SwipeAction.TRASH, prefs.swipeLeftActionNow())
        assertEquals(SwipeAction.ARCHIVE, prefs.swipeRightAction.first())
        assertEquals(SwipeAction.TRASH, prefs.swipeLeftAction.first())
    }

    @Test
    fun writes_roundTrip_andSurviveAFreshInstance() = runBlocking {
        val prefs = PreferencesRepository(context)
        prefs.setSwipeRightAction(SwipeAction.PIN)
        prefs.setSwipeLeftAction(SwipeAction.OFF)
        assertEquals(SwipeAction.PIN, prefs.swipeRightActionNow())
        assertEquals(SwipeAction.OFF, prefs.swipeLeftActionNow())

        // A fresh repository over the same store sees the same values
        // (the DataStore delegate is a top-level singleton per file).
        val fresh = PreferencesRepository(context)
        assertEquals(SwipeAction.PIN, fresh.swipeRightAction.first())
        assertEquals(SwipeAction.OFF, fresh.swipeLeftAction.first())

        // Restore the defaults so no state leaks across tests sharing
        // the sandbox store.
        prefs.setSwipeRightAction(SwipeAction.ARCHIVE)
        prefs.setSwipeLeftAction(SwipeAction.TRASH)
        Unit
    }
}
