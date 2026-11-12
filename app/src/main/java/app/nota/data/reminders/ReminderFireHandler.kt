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
 *
 * Phase 7 (PR #53): a REPEATING reminder that fires still posts as
 * above, but instead of spending it advances to its next occurrence
 * and is re-armed under the same id; the spent path (missing/trashed
 * note) ends the series exactly as before. If the user rescheduled
 * the reminder while this fire was in flight, markFired reports a
 * conflict instead: the live row is left untouched and the armed
 * set is re-converged to it — dropping the fired bookkeeping
 * (onFired) would disarm the replacement alarm the reschedule
 * just armed (round 5).
 */
class ReminderFireHandler(
    context: Context,
    private val provider: DatabaseProvider,
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
) {

    private val appContext = context.applicationContext

    suspend fun onAlarmFired(
        reminderId: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
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
            repository.markSpent(reminder)
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
        when (val outcome = repository.markFired(reminder, nowMillis)) {
            is ReminderRepository.FireOutcome.Advanced ->
                // Re-arm the next occurrence under the same id (the
                // arm replaces the consumed alarm's PendingIntent).
                scheduler.schedule(outcome.reminder)
            ReminderRepository.FireOutcome.Spent ->
                scheduler.onFired(reminderId)
            ReminderRepository.FireOutcome.Conflicted ->
                // The series was neither advanced nor spent: the
                // live row carries the user's rescheduled time.
                // Converge the armed set to the table (which arms
                // that time) rather than dropping the bookkeeping
                // — onFired would disarm the replacement alarm.
                scheduler.rescheduleAll()
        }
    }
}
