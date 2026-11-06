package app.nota.feature.widget

import android.content.Intent
import app.nota.MainActivity
import app.nota.data.capture.CaptureIntents
import app.nota.data.capture.CaptureSource
import app.nota.data.reminders.PendingNoteOpenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 5.3: the widget's tap wiring — every target must be an
 * existing door, never a bespoke path. The capture buttons build
 * the same CaptureIntents door intents the shortcuts and QS tile
 * fire (stamped with the widget's own capture source), and the
 * Today area's note-open carries the 5.1 PendingNoteOpenStore
 * extra, so a widget tap meets App Lock and the note's own gate
 * exactly like a reminder tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetIntentsRobolectricTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun newNoteButtonFiresTheNewNoteDoorAsAWidgetCapture() {
        val intent = WidgetIntents.newNoteIntent(context)
        assertEquals(CaptureIntents.ACTION_CAPTURE, intent.action)
        assertEquals(
            CaptureIntents.DOOR_NEW_NOTE,
            intent.getStringExtra(CaptureIntents.EXTRA_CAPTURE_ACTION),
        )
        assertEquals(
            "widget",
            intent.getStringExtra(CaptureIntents.EXTRA_CAPTURE_SOURCE),
        )
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertTrue(
            intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0,
        )
    }

    @Test
    fun todayButtonFiresTheTodayDoorAsAWidgetCapture() {
        val intent = WidgetIntents.todayDoorIntent(context)
        assertEquals(CaptureIntents.DOOR_TODAY, CaptureIntents.doorFrom(intent))
        assertEquals(CaptureSource.WIDGET, CaptureIntents.sourceFrom(intent))
    }

    @Test
    fun doorParserAcceptsWidgetIntents() {
        // MainActivity stages doors via CaptureIntents.doorFrom /
        // sourceFrom: a widget door must survive that exact parse.
        val intent = WidgetIntents.todayDoorIntent(context)
        assertEquals(CaptureIntents.DOOR_TODAY, CaptureIntents.doorFrom(intent))
        assertEquals(CaptureSource.WIDGET, CaptureIntents.sourceFrom(intent))
    }

    @Test
    fun todayNoteIntentUsesTheNotificationTapSeam() {
        val intent = WidgetIntents.todayNoteIntent(context, "note-42")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(
            "note-42",
            intent.getStringExtra(PendingNoteOpenStore.EXTRA_NOTE_ID),
        )
        assertEquals(MainActivity::class.java.name, intent.component?.className)
    }

    @Test
    fun widgetSourceRoundTripsItsWireValue() {
        assertEquals(CaptureSource.WIDGET, CaptureSource.fromWire("widget"))
        assertEquals("widget", CaptureSource.WIDGET.wire)
    }
}
