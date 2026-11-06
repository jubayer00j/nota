package app.nota.data.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.nota.MainActivity
import app.nota.notes.R

/**
 * Phase 5.1: posting reminder notifications. The channel mirrors
 * 1.x's: id "nota-reminders" (1.x: "noma-reminders"), name
 * "Note reminders", HIGH importance (4) — deliberate, as in 1.x,
 * because Android locks a channel's importance after creation.
 *
 * Built on the framework Notification.Builder (minSdk 26 covers the
 * channel APIs), so no new dependency is needed.
 */
object ReminderNotifications {

    const val CHANNEL_ID = "nota-reminders"
    const val CHANNEL_NAME = "Note reminders"
    const val CHANNEL_DESCRIPTION = "Reminders you set on notes"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = CHANNEL_DESCRIPTION },
            )
        }
    }

    /**
     * Posts the fired reminder's notification. [title] / [text] must
     * already be the privacy-safe snapshot from
     * [ReminderNotificationText] (generic pair for locked notes);
     * locked notes additionally get VISIBILITY_SECRET so nothing of
     * the notification shows on a secure lock screen. The tap opens
     * the note through MainActivity (App Lock + the note's own lock
     * gate apply as usual). No action buttons in 5.1 (1.x has none).
     */
    fun post(
        context: Context,
        reminderId: String,
        noteId: String,
        title: String,
        text: String,
        noteLocked: Boolean,
    ) {
        ensureChannel(context)
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(PendingNoteOpenStore.EXTRA_NOTE_ID, noteId)
        }
        val tapPendingIntent = PendingIntent.getActivity(
            context,
            ReminderIds.notificationId(reminderId),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nota_mark)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapPendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(
                if (noteLocked) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PRIVATE,
            )
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(ReminderIds.notificationId(reminderId), notification)
    }

    /** Withdraws a posted notification (e.g. its reminder was removed
     *  before the user saw it). */
    fun cancel(context: Context, reminderId: String) {
        context.getSystemService(NotificationManager::class.java)
            .cancel(ReminderIds.notificationId(reminderId))
    }
}
