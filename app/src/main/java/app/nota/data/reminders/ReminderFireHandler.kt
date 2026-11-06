package app.nota.data.reminders

import android.content.Context
import app.nota.data.db.DatabaseProvider

/**
 * Phase 5.1: what happens when a reminder's alarm fires. Kept separate
 * from the BroadcastReceiver so the whole flow is testable.
 *
 * The lookup runs against the ACTIVE account's database: alarms are
 * device-global, so a stale alarm from another account (or a deleted
 * reminder) simply finds nothing and cleans itself up. A note that is
 * missing or trashed at fire time gets NO notification and its
 * reminder is spent (cleanup) — it can never fire twice or linger as
 * pending. Otherwise the notification posts with the privacy snapshot
 * (locked note ⇒ generic text only) and the one-shot is marked fired:
 * it leaves the pending state and the note's reminderAt clears.
 */
class ReminderFireHandler(
    context: Context,
    private val provider: DatabaseProvider,
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
) {

    private val appContext = context.applicationContext

    suspend fun onAlarmFired(reminderId: String) {
        val db = provider.current
        val reminder = db.reminderDao().getById(reminderId)
        if (reminder == null) {
            // Not this account's reminder (or already deleted): the
            // alarm was stale — just fix the bookkeeping.
            scheduler.onFired(reminderId)
            return
        }
        if (!reminder.enabled || reminder.alertType != ReminderAlertType.NOTIFICATION) {
            scheduler.onFired(reminderId)
            return
        }
        val note = db.noteDao().getById(reminder.noteId)
        if (note == null || note.trashedAt != null) {
            repository.markFired(reminder)
            scheduler.onFired(reminderId)
            return
        }
        val (title, text) = ReminderNotificationText.snapshot(
            noteTitle = note.title,
            noteLocked = note.locked,
            triggerAtMillis = reminder.triggerAt,
        )
        ReminderNotifications.post(
            context = appContext,
            reminderId = reminder.id,
            noteId = note.id,
            title = title,
            text = text,
            noteLocked = note.locked,
        )
        repository.markFired(reminder)
        scheduler.onFired(reminderId)
    }
}
