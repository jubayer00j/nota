package app.nota.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.nota.ui.components.NotaPrimaryButton
import app.nota.ui.components.NoteCard
import app.nota.ui.theme.NotaSpacing
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

/** Today surface (Phase 1b, spec §12): greeting, daily note, pinned, due reminders. */
@Composable
fun TodayScreen(
    onOpenNote: (String) -> Unit = {},
    // Editor Refinement #2 (fix round): a daily note created by the
    // "Open today's note" tap opens in the editor; an existing one
    // opens for reading.
    onOpenNewNote: (String) -> Unit = onOpenNote,
    viewModel: TodayViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val greeting = when (hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Good night"
    }
    val dateLabel = runCatching {
        LocalDate.parse(state.todayDate).format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.getDefault()))
    }.getOrDefault(state.todayDate)
    val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(NotaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(NotaSpacing.md),
    ) {
        item {
            Column {
                Text(greeting, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(NotaSpacing.xs))
                Text(
                    dateLabel,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NotaSpacing.md))
                NotaPrimaryButton(
                    text = "Open today's note",
                    onClick = { viewModel.openTodaysNote(onOpenNote, onOpenNewNote) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Text("Pinned", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (state.pinned.isEmpty()) {
            item {
                Text(
                    "No pinned notes yet. Pin a note to keep it here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.pinned, key = { it.id }) { note ->
                NoteCard(
                    title = note.title,
                    snippet = if (note.locked) "" else note.excerpt,
                    updatedLabel = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(note.updatedAt)),
                    pinned = true,
                    favourite = note.favourite,
                    locked = note.locked,
                    onClick = { onOpenNote(note.id) },
                )
            }
        }
        item {
            Spacer(Modifier.height(NotaSpacing.sm))
            Text("Due today", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (state.dueToday.isEmpty()) {
            item {
                Text(
                    "No reminders due today.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.dueToday, key = { it.reminder.id }) { due ->
                Column(Modifier.fillMaxWidth().padding(vertical = NotaSpacing.xs)) {
                    Text(due.noteTitle.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall)
                    Text(
                        timeFormat.format(Date(due.reminder.triggerAt)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
