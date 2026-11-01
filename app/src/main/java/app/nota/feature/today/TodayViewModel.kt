package app.nota.feature.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.db.NoteEntity
import app.nota.data.db.ReminderDao
import app.nota.data.db.ReminderEntity
import app.nota.data.repository.NoteRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DueReminder(val reminder: ReminderEntity, val noteTitle: String)

data class TodayUiState(
    val pinned: List<NoteEntity> = emptyList(),
    val dueToday: List<DueReminder> = emptyList(),
    val todayDate: String = LocalDate.now().toString(),
)

class TodayViewModel(
    private val repository: NoteRepository,
    reminderDao: ReminderDao,
) : ViewModel() {

    val uiState: StateFlow<TodayUiState> = combine(
        repository.observeActive(),
        reminderDao.observeEnabled(),
    ) { notes, reminders ->
        val zone = ZoneId.systemDefault()
        val startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfTomorrow = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val titles = notes.associate { it.id to it.title }
        TodayUiState(
            pinned = notes.filter { it.pinned },
            dueToday = reminders
                .filter { it.triggerAt >= startOfToday && it.triggerAt < startOfTomorrow }
                .map { DueReminder(it, titles[it.noteId] ?: "Untitled") },
            todayDate = LocalDate.now(zone).toString(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun openTodaysNote(onOpen: (String) -> Unit) {
        viewModelScope.launch {
            val note = repository.ensureDailyNote(LocalDate.now().toString())
            onOpen(note.id)
        }
    }
}
