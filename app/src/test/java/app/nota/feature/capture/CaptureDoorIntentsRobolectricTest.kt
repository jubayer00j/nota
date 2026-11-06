package app.nota.feature.capture

import android.content.ComponentName
import android.content.Intent
import app.nota.MainActivity
import app.nota.data.capture.CaptureIntake
import app.nota.data.capture.CaptureIntents
import app.nota.data.capture.CaptureRequest
import app.nota.data.capture.CaptureSource
import app.nota.data.capture.PendingCaptureStore
import app.nota.data.reminders.PendingNoteOpenStore
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Phase 5.2: door + pinned-note intent construction (action, extras,
 * component, flags), and CaptureIntake's staging rules — doors stage
 * synchronously stamped with the active account; a share intent
 * object is staged exactly once however often the framework
 * re-delivers it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaptureDoorIntentsRobolectricTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context: android.content.Context get() = RuntimeEnvironment.getApplication()

    private fun store() = PendingCaptureStore(File(tempFolder.root, "pending_captures"))

    @Test
    fun doorIntent_carriesActionExtrasComponentAndFlags() {
        val intent = CaptureIntents.doorIntent(
            context, CaptureIntents.DOOR_TODAY, CaptureSource.TILE,
        )
        assertEquals(CaptureIntents.ACTION_CAPTURE, intent.action)
        assertEquals(
            ComponentName(context, MainActivity::class.java),
            intent.component,
        )
        assertEquals(CaptureIntents.DOOR_TODAY, CaptureIntents.doorFrom(intent))
        assertEquals(CaptureSource.TILE, CaptureIntents.sourceFrom(intent))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun doorFrom_rejectsUnknownDoorsAndForeignIntents() {
        val unknown = CaptureIntents.doorIntent(
            context, CaptureIntents.DOOR_NEW_NOTE, CaptureSource.SHORTCUT,
        ).apply { putExtra(CaptureIntents.EXTRA_CAPTURE_ACTION, "voice-note") }
        assertNull(CaptureIntents.doorFrom(unknown))
        assertNull(CaptureIntents.doorFrom(Intent(Intent.ACTION_MAIN)))
        assertNull(CaptureIntents.doorFrom(null))
    }

    @Test
    fun noteOpenIntent_usesTheNotificationTapSeam() {
        val intent = CaptureIntents.noteOpenIntent(context, "note-42")
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(
            "note-42",
            intent.getStringExtra(PendingNoteOpenStore.EXTRA_NOTE_ID),
        )
        assertEquals(
            ComponentName(context, MainActivity::class.java),
            intent.component,
        )
    }

    @Test
    fun stageDoor_stampsTheActiveAccount() {
        val store = store()
        val intake = CaptureIntake(context, store)
        intake.stageDoor(CaptureIntents.DOOR_TODAY, CaptureSource.SHORTCUT, "uid-a")
        val record = store.head()
        assertEquals("uid-a", record?.accountId)
        assertTrue(record?.request is CaptureRequest.Today)
        assertEquals(CaptureSource.SHORTCUT, record?.request?.source)
    }

    @Test
    fun stageShare_sameIntentObjectStagesOnce_distinctIntentStagesAgain() {
        runBlocking {
            val store = store()
            val intake = CaptureIntake(context, store)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "hello")
            }
            intake.stageShare(share, "guest")
            withTimeout(10_000) {
                while (store.records.value.isEmpty()) delay(25)
            }
            assertEquals(1, store.records.value.size)
            assertTrue(store.records.value[0].request is CaptureRequest.Share)

            // The framework re-delivering the SAME object is not a
            // second share (1.x's identity guard).
            intake.stageShare(share, "guest")
            delay(300)
            assertEquals(1, store.records.value.size)

            // A distinct intent object IS a second share.
            val second = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "hello again")
            }
            intake.stageShare(second, "guest")
            withTimeout(10_000) {
                while (store.records.value.size < 2) delay(25)
            }
            assertEquals(2, store.records.value.size)
        }
    }
}
