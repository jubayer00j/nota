package app.nota.feature.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.reminders.ReminderNotifications
import app.nota.data.reminders.ReminderRepository
import app.nota.data.reminders.ReminderScheduler
import android.content.Context
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Phase 5.1: the Reminders screen's state — the active account's
 * reminders in 1.x's three sections (Overdue / Upcoming /
 * Completed-Dismissed), each sorted by time ascending. Rows come
 * from ReminderRepository.observeRows (locked notes show their
 * titles here — the list is in-app behind App Lock, as in 1.x).
 * Creating this ViewModel runs one scheduler reconciliation, so a
 * phone-alarm repeat whose Clock occurrence has already passed is
 * advanced before its row can be shown as overdue.
 */
class RemindersViewModel(
    private val repository: ReminderRepository,
    private val scheduler: ReminderScheduler,
    private val appContext: Context,
) : ViewModel() {

    data class UiState(
        val overdue: List<ReminderRepository.ReminderRow> = emptyList(),
        val upcoming: List<ReminderRepository.ReminderRow> = emptyList(),
        val past: List<ReminderRepository.ReminderRow> = emptyList(),
    ) {
        val isEmpty: Boolean get() = overdue.isEmpty() && upcoming.isEmpty() && past.isEmpty()
    }

    val uiState: StateFlow<UiState> = repository.observeRows()
        .map { rows -> section(rows, System.currentTimeMillis()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        // Opening this screen is the moment a stale repeating row
        // would be seen: the Clock app gives Nota no fire signal for
        // phone-alarm reminders, so a phone-alarm repeat's row is
        // advanced only by the scheduler's reconciliation funnel.
        // Run it once here so the row heals before it can be filed
        // under Overdue (notification repeats are advanced by the
        // fire handler instead and are unaffected).
        viewModelScope.launch {
            runCatching { scheduler.rescheduleAll() }
        }
    }

    /** Marks a pending reminder completed (1.x): it leaves the
     *  pending state and its alarm is cancelled. For phone-alarm
     *  reminders the Clock-app alarm itself is untouched — it lives
     *  in the Clock app (the row's UI says so). */
    fun complete(row: ReminderRepository.ReminderRow) {
        viewModelScope.launch {
            repository.setEnabled(row.reminder, enabled = false)
            scheduler.cancel(row.reminder.id)
        }
    }

    /** Reopens a completed/dismissed reminder (1.x): pending again,
     *  and re-armed when its time is still ahead (1.x never schedules
     *  a past time or a phone-alarm). */
    fun reopen(row: ReminderRepository.ReminderRow) {
        viewModelScope.launch {
            val reopened = row.reminder.copy(enabled = true)
            repository.setEnabled(row.reminder, enabled = true)
            if (reopened.triggerAt > System.currentTimeMillis()) {
                scheduler.schedule(reopened)
            }
        }
    }

    /** Deletes the reminder row entirely (alarm cancelled, any
     *  posted notification withdrawn). */
    fun delete(row: ReminderRepository.ReminderRow) {
        viewModelScope.launch {
            repository.deleteReminder(row.reminder)
            scheduler.cancel(row.reminder.id)
            ReminderNotifications.cancel(appContext, row.reminder.id)
        }
    }

    companion object {
        /** The section split, pure for testability. */
        fun section(
            rows: List<ReminderRepository.ReminderRow>,
            nowMillis: Long,
        ): UiState {
            val sorted = rows.sortedBy { it.reminder.triggerAt }
            return UiState(
                overdue = sorted.filter { it.reminder.enabled && it.reminder.triggerAt < nowMillis },
                upcoming = sorted.filter { it.reminder.enabled && it.reminder.triggerAt >= nowMillis },
                past = sorted.filter { !it.reminder.enabled },
            )
        }
    }
}
