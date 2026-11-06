package app.nota.data.reminders

import app.nota.data.db.ReminderEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.1: the pure reconciliation rule behind rescheduleAll —
 * 1.x's rehydrate semantics: arm enabled, notification-type, future
 * reminders on live notes; never arm phone-alarms (the Clock app owns
 * them), spent rows, overdue rows (they sit in Overdue instead), or
 * reminders on trashed/missing notes; disarm anything armed that is
 * no longer desired (e.g. a previous account's leftovers).
 */
class ReminderSchedulerTest {

    private val now = 1_800_000_000_000L

    private fun reminder(
        id: String,
        triggerAt: Long = now + 60_000,
        enabled: Boolean = true,
        alertType: String = ReminderAlertType.NOTIFICATION,
        noteId: String = "note-$id",
    ) = ReminderEntity(
        id = id,
        noteId = noteId,
        triggerAt = triggerAt,
        repeatRule = null,
        enabled = enabled,
        createdAt = now - 1_000,
        alertType = alertType,
    )

    private fun candidate(
        reminder: ReminderEntity,
        noteMissingOrTrashed: Boolean = false,
    ) = ReminderArmCandidate(reminder, noteMissingOrTrashed)

    @Test
    fun armsFutureEnabledNotificationReminderOnLiveNote() {
        val plan = ReminderReconcile.plan(
            listOf(candidate(reminder("r1"))),
            armedIds = emptySet(),
            nowMillis = now,
        )
        assertEquals(listOf("r1"), plan.arm.map { it.id })
        assertEquals(setOf("r1"), plan.desiredIds)
        assertTrue(plan.disarm.isEmpty())
    }

    @Test
    fun neverArmsPhoneAlarmSpentOverdueOrTrashedNoteReminders() {
        val plan = ReminderReconcile.plan(
            listOf(
                candidate(reminder("phone", alertType = ReminderAlertType.PHONE_ALARM)),
                candidate(reminder("spent", enabled = false)),
                candidate(reminder("overdue", triggerAt = now - 1)),
                candidate(reminder("trashed-note"), noteMissingOrTrashed = true),
                candidate(reminder("ok")),
            ),
            armedIds = emptySet(),
            nowMillis = now,
        )
        assertEquals(listOf("ok"), plan.arm.map { it.id })
        assertEquals(setOf("ok"), plan.desiredIds)
    }

    @Test
    fun reminderExactlyAtNowIsNotArmed() {
        val plan = ReminderReconcile.plan(
            listOf(candidate(reminder("edge", triggerAt = now))),
            armedIds = emptySet(),
            nowMillis = now,
        )
        assertTrue(plan.arm.isEmpty())
    }

    @Test
    fun disarmsArmedIdsThatAreNoLongerDesired() {
        val plan = ReminderReconcile.plan(
            listOf(candidate(reminder("kept"))),
            armedIds = setOf("kept", "other-account-leftover", "deleted-reminder"),
            nowMillis = now,
        )
        assertEquals(setOf("other-account-leftover", "deleted-reminder"), plan.disarm)
        assertEquals(setOf("kept"), plan.desiredIds)
    }

    @Test
    fun emptyWorldDisarmsEverything() {
        val plan = ReminderReconcile.plan(
            candidates = emptyList(),
            armedIds = setOf("a", "b"),
            nowMillis = now,
        )
        assertTrue(plan.arm.isEmpty())
        assertEquals(setOf("a", "b"), plan.disarm)
        assertTrue(plan.desiredIds.isEmpty())
    }

    @Test
    fun editedReminderIsReArmedWithItsNewTime() {
        val edited = reminder("r1", triggerAt = now + 3_600_000)
        val plan = ReminderReconcile.plan(
            listOf(candidate(edited)),
            armedIds = setOf("r1"),
            nowMillis = now,
        )
        // Same id stays desired (no disarm) but is re-armed so the
        // AlarmManager call replaces the old trigger time.
        assertEquals(listOf(edited), plan.arm)
        assertTrue(plan.disarm.isEmpty())
    }
}
