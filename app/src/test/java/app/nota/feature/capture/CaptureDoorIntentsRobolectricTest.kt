package app.nota.feature.capture

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import app.nota.MainActivity
import app.nota.data.attachments.isAcceptableImportUri
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
import org.junit.Assert.assertFalse
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
        ).apply { putExtra(CaptureIntents.EXTRA_CAPTURE_ACTION, "dictate") }
        assertNull(CaptureIntents.doorFrom(unknown))
        assertNull(CaptureIntents.doorFrom(Intent(Intent.ACTION_MAIN)))
        assertNull(CaptureIntents.doorFrom(null))
    }

    // Phase 5.4: the voice door joins the allowlist and stages a
    // VoiceNote through the same synchronous door path — the note
    // itself only exists once the shell's listening sheet saves it.
    @Test
    fun voiceDoorIntent_carriesVoiceDoorAndSource() {
        val intent = CaptureIntents.doorIntent(
            context, CaptureIntents.DOOR_VOICE_NOTE, CaptureSource.TILE,
        )
        assertEquals(
            CaptureIntents.DOOR_VOICE_NOTE,
            CaptureIntents.doorFrom(intent),
        )
        assertEquals(CaptureSource.TILE, CaptureIntents.sourceFrom(intent))
        val shortcutIntent = CaptureIntents.doorIntent(
            context, CaptureIntents.DOOR_VOICE_NOTE, CaptureSource.SHORTCUT,
        )
        assertEquals(
            CaptureIntents.DOOR_VOICE_NOTE,
            CaptureIntents.doorFrom(shortcutIntent),
        )
    }

    @Test
    fun stageDoor_voice_stagesVoiceNoteSynchronously() {
        val store = store()
        val intake = CaptureIntake(context, store)
        intake.stageDoor(
            CaptureIntents.DOOR_VOICE_NOTE, CaptureSource.TILE, "uid-b",
        )
        val record = store.head()
        assertEquals("uid-b", record?.accountId)
        assertTrue(record?.request is CaptureRequest.VoiceNote)
        assertEquals(CaptureSource.TILE, record?.request?.source)
    }

    @Test
    fun voiceNoteRecord_jsonRoundTrips() {
        val store = store()
        val intake = CaptureIntake(context, store)
        intake.stageDoor(
            CaptureIntents.DOOR_VOICE_NOTE, CaptureSource.SHORTCUT, "guest",
        )
        val record = store.head()!!
        val parsed = app.nota.data.capture.CaptureRecord.parse(record.toJson())
        assertEquals(record, parsed)
        assertTrue(parsed?.request is CaptureRequest.VoiceNote)
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

    // PR #55 (Argon F-01): a share carrying a file:// stream must
    // never have that URI opened with Nota's own file access — the
    // image is skipped and the rest of the share proceeds.
    @Test
    fun stageShare_fileSchemeStream_isNeverOpened() {
        runBlocking {
            val store = store()
            val intake = CaptureIntake(context, store)
            val payload = tempFolder.newFile("payload.png").apply {
                writeBytes(byteArrayOf(1, 2, 3, 4))
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(payload))
                putExtra(Intent.EXTRA_TEXT, "kept text")
            }
            intake.stageShare(share, "guest")
            withTimeout(10_000) {
                while (store.records.value.isEmpty()) delay(25)
            }
            val record = store.records.value.single()
            val request = record.request
            assertTrue(request is CaptureRequest.Share)
            assertTrue((request as CaptureRequest.Share).images.isEmpty())
            val stagingDir = File(File(tempFolder.root, "pending_captures"), record.id)
            assertTrue(
                stagingDir.listFiles()?.none { it.name.startsWith("img-") } ?: true,
            )
        }
    }

    // PR #55 (Argon F-01): the import-scheme predicate itself —
    // content:// only, every other scheme refused.
    @Test
    fun importUriPredicate_acceptsContentOnly() {
        assertTrue(isAcceptableImportUri(Uri.parse("content://media/external/images/1")))
        assertFalse(isAcceptableImportUri(Uri.parse("file:///data/data/app.nota.notes/databases/x")))
        assertFalse(isAcceptableImportUri(Uri.parse("https://example.com/a.png")))
        assertFalse(isAcceptableImportUri(Uri.parse("android.resource://app.nota.notes/1")))
    }

    // PR #55 (Argon F-12): a share that yields nothing degrades to
    // a plain new-note capture AND leaves no orphan staging
    // directory behind.
    @Test
    fun stageShare_emptyShare_degradesToNewNote_withoutStagingDir() {
        runBlocking {
            val store = store()
            val intake = CaptureIntake(context, store)
            val payload = tempFolder.newFile("rejected.png").apply {
                writeBytes(byteArrayOf(9, 9, 9))
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(payload))
            }
            intake.stageShare(share, "guest")
            withTimeout(10_000) {
                while (store.records.value.isEmpty()) delay(25)
            }
            val record = store.records.value.single()
            assertTrue(record.request is CaptureRequest.NewNote)
            assertFalse(
                File(File(tempFolder.root, "pending_captures"), record.id).exists(),
            )
        }
    }
}
