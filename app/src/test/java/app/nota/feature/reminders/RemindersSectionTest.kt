package app.nota.feature.reminders

import app.nota.data.db.ReminderEntity
import app.nota.data.reminders.ReminderAlertType
import app.nota.data.reminders.ReminderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5.1: the Reminders screen's section split (1.x's reminders
 * view): pending + past → Overdue, pending + future → Upcoming,
 * spent → Completed/Dismissed; each sorted by time ascending.
 */
class RemindersSectionTest {

    private val now = 1_800_000_000_000L

    private fun row(
        id: String,
        triggerAt: Long,
        enabled: Boolean = true,
        alertType: String = ReminderAlertType.NOTIFICATION,
    ) = ReminderRepository.ReminderRow(
        reminder = ReminderEntity(
            id = id,
            noteId = "note-$id",
            triggerAt = triggerAt,
            repeatRule = null,
            enabled = enabled,
            createdAt = now - 10_000,
            alertType = alertType,
        ),
        noteTitle = "Note $id",
        noteLocked = false,
    )

    @Test
    fun splitsIntoOverdueUpcomingAndPast() {
        val state = RemindersViewModel.section(
            listOf(
                row("future", now + 60_000),
                row("past-due", now - 60_000),
                row("spent", now + 120_000, enabled = false),
                row("spent-overdue-time", now - 120_000, enabled = false),
            ),
            nowMillis = now,
        )
        assertEquals(listOf("past-due"), state.overdue.map { it.reminder.id })
        assertEquals(listOf("future"), state.upcoming.map { it.reminder.id })
        assertEquals(
            listOf("spent-overdue-time", "spent"),
            state.past.map { it.reminder.id },
        )
    }

    @Test
    fun sectionsAreSortedByTimeAscending() {
        val state = RemindersViewModel.section(
            listOf(
                row("c", now + 3_000),
                row("a", now + 1_000),
                row("b", now + 2_000),
            ),
            nowMillis = now,
        )
        assertEquals(listOf("a", "b", "c"), state.upcoming.map { it.reminder.id })
    }

    @Test
    fun emptyRowsMeanEmptyState() {
        val state = RemindersViewModel.section(emptyList(), nowMillis = now)
        assertTrue(state.isEmpty)
    }

    @Test
    fun phoneAlarmRowsAreListedLikeAnyOther() {
        val state = RemindersViewModel.section(
            listOf(row("alarm", now + 60_000, alertType = ReminderAlertType.PHONE_ALARM)),
            nowMillis = now,
        )
        assertEquals(listOf("alarm"), state.upcoming.map { it.reminder.id })
    }
}
