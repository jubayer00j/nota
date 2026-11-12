package app.nota.feature.reminders

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.nota.data.db.ReminderEntity
import app.nota.data.reminders.ReminderAlertType
import app.nota.data.reminders.ReminderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowApplication

/**
 * Phase 5.1: the Reminders screen's section split (1.x's reminders
 * view): pending + past → Overdue, pending + future → Upcoming,
 * spent → Completed/Dismissed; each sorted by time ascending.
 *
 * PR #53: the class also carries the reminder dialog's repeat pins —
 * it is the closest registered seam for the dialog (the dialog has
 * no test class of its own and CI enumerates classes), so it runs
 * under Robolectric like the repository tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemindersSectionTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

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

    // --- PR #53: the dialog's Repeat row ---------------------------

    @Test
    fun dialogDailyChipSavesTheDailyRule() {
        // Granted up front so Save goes straight to performSave
        // instead of the permission launcher.
        ShadowApplication.getInstance()
            .grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        var savedRule: String? = "unset"
        composeRule.setContent {
            ReminderDialog(
                noteTitle = "Dentist",
                existing = null,
                onDismiss = {},
                onSave = { _, _, _, repeatRule -> savedRule = repeatRule },
                onRemove = null,
            )
        }
        composeRule.onNodeWithText("Daily").performClick()
        composeRule.onNodeWithText("Save Reminder").performClick()
        assertEquals("DAILY", savedRule)
    }

    @Test
    fun dialogEditingAWeeklyReminderPreSelectsWeekly() {
        val existing = ReminderEntity(
            id = "r-weekly",
            noteId = "note-1",
            triggerAt = System.currentTimeMillis() + 3_600_000L,
            repeatRule = "WEEKLY",
            enabled = true,
            createdAt = 1L,
            alertType = ReminderAlertType.NOTIFICATION,
        )
        composeRule.setContent {
            ReminderDialog(
                noteTitle = "Dentist",
                existing = existing,
                onDismiss = {},
                onSave = { _, _, _, _ -> },
                onRemove = {},
            )
        }
        composeRule.onNodeWithText("Weekly").assertIsSelected()
    }
}
