package app.nota.feature.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.nota.data.capture.CaptureIntents
import app.nota.data.capture.CaptureSource

/**
 * Phase 5.3: the widget's tap targets. Every tap reuses an existing
 * door — nothing here invents a path into the app:
 *
 *  - The capture buttons fire the SAME [CaptureIntents] door intents
 *    the launcher shortcuts and QS tile fire (staged by MainActivity,
 *    delivered by the shell only while unlocked), stamped with the
 *    widget's own [CaptureSource] so a widget capture is
 *    attributable in the queue.
 *  - The Today area, when it shows a readable note, opens it through
 *    [CaptureIntents.noteOpenIntent] — the 5.1 notification-tap seam
 *    (PendingNoteOpenStore), so App Lock and the note's own gate
 *    apply exactly as for a reminder tap or a pinned shortcut.
 *
 * Each target gets its own PendingIntent request code so the three
 * can never overwrite one another (1.x's widget discipline).
 */
object WidgetIntents {

    private const val REQUEST_NEW_NOTE = 5301
    private const val REQUEST_TODAY_DOOR = 5302
    private const val REQUEST_TODAY_NOTE = 5303

    fun newNoteIntent(context: Context): Intent =
        CaptureIntents.doorIntent(context, CaptureIntents.DOOR_NEW_NOTE, CaptureSource.WIDGET)

    fun todayDoorIntent(context: Context): Intent =
        CaptureIntents.doorIntent(context, CaptureIntents.DOOR_TODAY, CaptureSource.WIDGET)

    fun todayNoteIntent(context: Context, noteId: String): Intent =
        CaptureIntents.noteOpenIntent(context, noteId)

    fun newNotePendingIntent(context: Context): PendingIntent =
        pending(context, REQUEST_NEW_NOTE, newNoteIntent(context))

    fun todayDoorPendingIntent(context: Context): PendingIntent =
        pending(context, REQUEST_TODAY_DOOR, todayDoorIntent(context))

    fun todayNotePendingIntent(context: Context, noteId: String): PendingIntent =
        pending(context, REQUEST_TODAY_NOTE, todayNoteIntent(context, noteId))

    private fun pending(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
