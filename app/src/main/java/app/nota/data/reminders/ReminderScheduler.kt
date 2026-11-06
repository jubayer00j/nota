package app.nota.data.reminders

import app.nota.data.db.DatabaseProvider
import app.nota.data.db.ReminderEntity

/**
 * Phase 5.1: the platform seam under [ReminderScheduler]. The Android
 * implementation drives AlarmManager; tests substitute a fake and
 * assert arm/disarm calls without a device.
 *
 * The gateway also persists the set of reminder ids it has armed:
 * alarms are device-global while Nota's databases are per-account, so
 * after an account switch the scheduler can only disarm the previous
 * account's PendingIntents if it remembers their ids (the request
 * codes derive from the ids, but AlarmManager.cancel needs the intent,
 * which needs the id).
 */
interface ReminderAlarmGateway {
    /** Arms (or re-arms) the one-shot alarm for [reminderId]. */
    fun arm(reminderId: String, triggerAtMillis: Long)

    /** Cancels the alarm + PendingIntent for [reminderId]. */
    fun disarm(reminderId: String)

    /** The reminder ids this gateway believes are currently armed. */
    fun armedReminderIds(): Set<String>

    /** Persists the armed-id set after a reconciliation. */
    fun noteArmedReminderIds(ids: Set<String>)

    /** API 31+ exact-alarm permission; always true below 31. */
    fun canScheduleExactAlarms(): Boolean
}

/** A reminder plus the note-visibility fact the arm decision needs. */
data class ReminderArmCandidate(
    val reminder: ReminderEntity,
    val noteMissingOrTrashed: Boolean,
)

/**
 * The pure reconciliation rule (1.x `rehydrateReminders` parity):
 * arm every ENABLED, notification-type reminder whose time is still
 * in the future and whose note is present and untrashed; phone-alarm
 * reminders are never armed (the Clock app owns them); overdue
 * pending reminders are NOT armed late — they sit in the Reminders
 * screen's Overdue section, as in 1.x. Everything armed that is no
 * longer desired is disarmed.
 */
object ReminderReconcile {

    data class Plan(
        val arm: List<ReminderEntity>,
        val disarm: Set<String>,
        val desiredIds: Set<String>,
    )

    fun plan(
        candidates: List<ReminderArmCandidate>,
        armedIds: Set<String>,
        nowMillis: Long,
    ): Plan {
        val desired = candidates
            .filter { candidate ->
                val reminder = candidate.reminder
                reminder.enabled &&
                    reminder.alertType == ReminderAlertType.NOTIFICATION &&
                    reminder.triggerAt > nowMillis &&
                    !candidate.noteMissingOrTrashed
            }
            .map { it.reminder }
        val desiredIds = desired.map { it.id }.toSet()
        return Plan(
            arm = desired,
            disarm = armedIds - desiredIds,
            desiredIds = desiredIds,
        )
    }
}

/**
 * The single scheduling engine. UI paths call [schedule] / [cancel]
 * for immediacy; [rescheduleAll] is the reconciliation funnel for
 * every other change path (boot, time changes, restores, sync merges,
 * account switches, trash/restore) — driven by ReminderReconciler's
 * table observers plus explicit calls at app start and in the
 * broadcast receivers.
 */
class ReminderScheduler(
    private val gateway: ReminderAlarmGateway,
    private val provider: DatabaseProvider,
) {

    /** Arms one reminder (no-op for spent rows and phone-alarms —
     *  the Clock app owns those). Re-arming replaces the previous
     *  alarm for the same PendingIntent, so edits are safe. */
    fun schedule(reminder: ReminderEntity) {
        if (!reminder.enabled || reminder.alertType != ReminderAlertType.NOTIFICATION) return
        gateway.arm(reminder.id, reminder.triggerAt)
        gateway.noteArmedReminderIds(gateway.armedReminderIds() + reminder.id)
    }

    fun cancel(reminderId: String) {
        gateway.disarm(reminderId)
        gateway.noteArmedReminderIds(gateway.armedReminderIds() - reminderId)
    }

    /** The alarm for [reminderId] has fired: drop it from the armed
     *  bookkeeping (the alarm itself is consumed by firing). */
    fun onFired(reminderId: String) {
        gateway.disarm(reminderId)
        gateway.noteArmedReminderIds(gateway.armedReminderIds() - reminderId)
    }

    fun canScheduleExactAlarms(): Boolean = gateway.canScheduleExactAlarms()

    /**
     * Cancel-and-rebuild against the ACTIVE account's database:
     * disarms everything the plan no longer wants (including a
     * previous account's leftovers, via the persisted armed set),
     * arms the desired set, and persists the result.
     */
    suspend fun rescheduleAll(nowMillis: Long = System.currentTimeMillis()) {
        val db = provider.current
        val noteDao = db.noteDao()
        val candidates = db.reminderDao().getAll().map { reminder ->
            val note = noteDao.getById(reminder.noteId)
            ReminderArmCandidate(
                reminder = reminder,
                noteMissingOrTrashed = note == null || note.trashedAt != null,
            )
        }
        val plan = ReminderReconcile.plan(candidates, gateway.armedReminderIds(), nowMillis)
        plan.disarm.forEach { gateway.disarm(it) }
        plan.arm.forEach { gateway.arm(it.id, it.triggerAt) }
        gateway.noteArmedReminderIds(plan.desiredIds)
    }
}
