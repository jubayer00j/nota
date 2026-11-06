package app.nota.data.capture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.nota.MainActivity
import app.nota.data.reminders.PendingNoteOpenStore

/**
 * Phase 5.2: the intent contracts for every quick-capture door.
 *
 * Doors (launcher shortcuts, the QS tile) fire an explicit intent at
 * MainActivity carrying a door action extra; MainActivity stages it
 * in [PendingCaptureStore] and the shell delivers it after unlock —
 * the native-Kotlin shape of 1.x's deep-link doors
 * (app.noma.notes://new-note / //today), with the same user-visible
 * behaviour and no new browsable surface. The static shortcut set in
 * res/xml/shortcuts.xml declares these same extras inline.
 */
object CaptureIntents {

    const val ACTION_CAPTURE = "app.nota.action.CAPTURE"
    const val EXTRA_CAPTURE_ACTION = "app.nota.extra.CAPTURE_ACTION"
    const val EXTRA_CAPTURE_SOURCE = "app.nota.extra.CAPTURE_SOURCE"

    const val DOOR_NEW_NOTE = "new-note"
    const val DOOR_TODAY = "today"

    /** The Phase 5.4 voice door: lands in the listening sheet. */
    const val DOOR_VOICE_NOTE = "voice-note"

    /** The intent a door (QS tile today, anything in code tomorrow)
     *  fires at MainActivity. NEW_TASK | CLEAR_TOP, as 1.x's tiles. */
    fun doorIntent(context: Context, door: String, source: CaptureSource): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_CAPTURE
            putExtra(EXTRA_CAPTURE_ACTION, door)
            putExtra(EXTRA_CAPTURE_SOURCE, source.wire)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

    /** The door an intent asks for, or null when it isn't a door
     *  intent (or names a door Nota doesn't answer — 1.x validates
     *  its deep links against the same allowlist). */
    fun doorFrom(intent: Intent?): String? {
        if (intent?.action != ACTION_CAPTURE) return null
        return intent.getStringExtra(EXTRA_CAPTURE_ACTION)
            ?.takeIf {
                it == DOOR_NEW_NOTE || it == DOOR_TODAY || it == DOOR_VOICE_NOTE
            }
    }

    fun sourceFrom(intent: Intent?): CaptureSource =
        CaptureSource.fromWire(intent?.getStringExtra(EXTRA_CAPTURE_SOURCE))

    /**
     * Opens [noteId] through the 5.1 notification-tap seam (the
     * [PendingNoteOpenStore] extra on MainActivity, same flags as
     * [app.nota.data.reminders.ReminderNotifications]' tap intent),
     * so a pinned shortcut meets App Lock and the note's own lock
     * gate exactly like a reminder tap.
     */
    fun noteOpenIntent(context: Context, noteId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PendingNoteOpenStore.EXTRA_NOTE_ID, noteId)
        }
}

/** A share intent reduced to its raw pieces (byte copying is
 *  [CaptureIntake]'s job, on its background executor). */
data class ParsedShare(
    val subject: String?,
    val text: String?,
    val streamUris: List<Uri>,
)

/** ACTION_SEND / SEND_MULTIPLE extraction — the intent-reading half
 *  of 1.x's NomaSharePlugin.extractPayload. */
object ShareIntents {

    fun isShare(intent: Intent?): Boolean =
        intent?.action == Intent.ACTION_SEND ||
            intent?.action == Intent.ACTION_SEND_MULTIPLE

    fun parse(intent: Intent): ParsedShare {
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
            ?.takeIf { it.isNotBlank() }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        return ParsedShare(
            subject = subject,
            text = text,
            streamUris = collectStreamUris(intent),
        )
    }

    private fun collectStreamUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            val streams: List<Uri?>? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            // 1.x skips null entries rather than failing the share.
            streams?.forEach { uri -> if (uri != null) uris += uri }
        } else {
            val stream: Uri? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (stream != null) uris += stream
        }
        return uris
    }
}
