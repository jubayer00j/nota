package app.nota.feature.reminders

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nota.data.reminders.AlarmToneSettings
import app.nota.data.reminders.ReminderAlertType
import app.nota.data.reminders.ReminderNotificationText
import app.nota.data.reminders.ReminderRepeat
import app.nota.data.reminders.ReminderRepository
import app.nota.ui.theme.NotaSpacing
import java.time.ZoneId
import org.koin.androidx.compose.koinViewModel

/**
 * Phase 5.1: the Reminders tab — the active account's reminders in
 * 1.x's three sections (Overdue / Upcoming / Completed-Dismissed).
 * Locked notes show their titles here: the list is in-app behind App
 * Lock, exactly as in 1.x's reminders view (only the system
 * notification hides them). Tapping a row opens the note.
 */
@Composable
fun RemindersScreen(
    onOpenNote: (String) -> Unit = {},
    viewModel: RemindersViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    if (state.isEmpty) {
        Box(
            Modifier.fillMaxSize().padding(NotaSpacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Notifications,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NotaSpacing.md))
                Text("No reminders set", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(NotaSpacing.sm))
                Text(
                    "Create custom reminders on your notes to keep track of " +
                        "tasks, deadlines, and important thoughts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(NotaSpacing.sm))
                Text(
                    "Open a note → ⋮ → Set reminder",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(NotaSpacing.md),
        verticalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
    ) {
        item {
            Text("Reminders", style = MaterialTheme.typography.headlineMedium)
        }
        if (state.overdue.isNotEmpty()) {
            item { SectionHeader("Overdue", isOverdue = true) }
            items(state.overdue, key = { "overdue-${it.reminder.id}" }) { row ->
                ReminderRowItem(
                    row = row,
                    overdue = true,
                    onOpenNote = onOpenNote,
                    viewModel = viewModel,
                )
            }
        }
        if (state.upcoming.isNotEmpty()) {
            item { SectionHeader("Upcoming") }
            items(state.upcoming, key = { "upcoming-${it.reminder.id}" }) { row ->
                ReminderRowItem(
                    row = row,
                    overdue = false,
                    onOpenNote = onOpenNote,
                    viewModel = viewModel,
                )
            }
        }
        if (state.past.isNotEmpty()) {
            item { SectionHeader("Completed / Dismissed") }
            items(state.past, key = { "past-${it.reminder.id}" }) { row ->
                ReminderRowItem(
                    row = row,
                    overdue = false,
                    onOpenNote = onOpenNote,
                    viewModel = viewModel,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, isOverdue: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = if (isOverdue) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = Modifier.padding(top = NotaSpacing.sm),
    )
}

@Composable
private fun ReminderRowItem(
    row: ReminderRepository.ReminderRow,
    overdue: Boolean,
    onOpenNote: (String) -> Unit,
    viewModel: RemindersViewModel,
) {
    val context = LocalContext.current
    val reminder = row.reminder
    val isPhoneAlarm = reminder.alertType == ReminderAlertType.PHONE_ALARM
    Column(
        Modifier.fillMaxWidth()
            .clickable { onOpenNote(reminder.noteId) }
            .padding(vertical = NotaSpacing.xs),
    ) {
        Text(
            row.noteTitle.ifBlank { ReminderNotificationText.UNTITLED },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            ReminderNotificationText.formatListDateTime(reminder.triggerAt),
            style = MaterialTheme.typography.bodyMedium,
            color = if (overdue) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        // PR #53: the series label ("Daily", "Weekly · Tue", …);
        // absent for one-shots.
        val repeatLabel = ReminderRepeat.repeatLabel(
            reminder.repeatRule, reminder.triggerAt, ZoneId.systemDefault(),
        )
        if (repeatLabel != null) {
            Text(
                repeatLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isPhoneAlarm) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Alarm,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(horizontal = 2.dp))
                Text(
                    "Phone alarm",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (reminder.enabled) {
                TextButton(onClick = { viewModel.complete(row) }) { Text("Complete") }
                if (isPhoneAlarm) {
                    TextButton(
                        onClick = {
                            val intent = PhoneAlarmIntents.buildSetAlarmIntent(
                                row.noteTitle, reminder.triggerAt,
                                vibrate = reminder.alarmVibrate,
                                ringtoneUri = AlarmToneSettings
                                    .fromEntity(reminder).uri,
                                repeatDaysIso = ReminderRepeat.daysIso(
                                    reminder.repeatRule, reminder.triggerAt,
                                    ZoneId.systemDefault(),
                                ),
                                // PR #53 round 6: the row's stored
                                // series time is the picked intent —
                                // hand the Clock app that time, not
                                // a possibly gap-resolved trigger's.
                                pickedTime = ReminderRepeat
                                    .parseSeries(reminder.repeatRule)?.time,
                            )
                            val opened = PhoneAlarmIntents.canHandle(context, intent) &&
                                runCatching { context.startActivity(intent) }.isSuccess
                            if (!opened) {
                                Toast.makeText(
                                    context,
                                    "Couldn't open the Clock app.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    ) { Text("Clock app") }
                }
            } else {
                TextButton(onClick = { viewModel.reopen(row) }) { Text("Reopen") }
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    viewModel.delete(row)
                    if (isPhoneAlarm && reminder.enabled) {
                        Toast.makeText(
                            context,
                            "Reminder deleted. The alarm in your Clock app stays " +
                                "until you remove it there.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                },
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete reminder")
            }
        }
    }
}
