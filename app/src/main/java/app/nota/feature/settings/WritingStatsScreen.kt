package app.nota.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nota.data.repository.WritingStats
import app.nota.data.repository.WritingStatsRepository
import app.nota.ui.theme.NotaSpacing
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.koin.compose.koinInject

/**
 * "Your writing" (v0.37.0, spec §12): the quiet stats screen —
 * headline numbers, the streak, the last twelve weeks, this
 * week and month, and where the story starts. Calm copy only:
 * no gamification, no exhortations, nothing leaves the device
 * (the numbers are computed on-device from the notes table).
 * Reached from the Notes & writing sub-screen's row.
 */
@Composable
fun WritingStatsScreen(
    onBack: () -> Unit,
    statsRepository: WritingStatsRepository = koinInject(),
) {
    // Fix round 2 (R2-F1): a failed load used to swallow the
    // error and show a blank screen — a note over the
    // CursorWindow limit, a corrupt database, anything. The
    // load now has a real state, and a failure shows a calm
    // error with a retry instead of nothing.
    var retryCount by remember { mutableIntStateOf(0) }
    val state by produceState<LoadState>(initialValue = LoadState.Loading, retryCount) {
        value = runCatching { statsRepository.loadStats() }
            .fold(
                onSuccess = { LoadState.Loaded(it) },
                onFailure = { LoadState.Failed },
            )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = NotaSpacing.sm, vertical = NotaSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
            Text(
                "Your writing",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = NotaSpacing.md, vertical = NotaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NotaSpacing.md),
        ) {
            when (val loaded = state) {
                LoadState.Loading -> Unit // Local read; a frame of quiet.
                is LoadState.Loaded -> if (loaded.stats.isEmpty) {
                    Text(
                        "Your writing story starts with your first note.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    WritingStatsContent(loaded.stats)
                }
                LoadState.Failed -> {
                    Text(
                        "Couldn't load your writing stats.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { retryCount++ }) {
                        Text("Try again")
                    }
                }
            }
        }
    }
}

@Composable
private fun WritingStatsContent(stats: WritingStats) {
    SettingsCard {
        Row(Modifier.fillMaxWidth()) {
            HeadlineStat(
                formatCount(stats.totalNotes), "Notes",
                Modifier.weight(1f),
            )
            HeadlineStat(
                formatCount(stats.totalWords), "Words",
                Modifier.weight(1f),
            )
            HeadlineStat(
                formatCount(stats.writingDays), "Writing days",
                Modifier.weight(1f),
            )
        }
    }
    SettingsCard(title = "Streak") {
        StatsRow("Current streak", dayCount(stats.currentStreak))
        StatsRow("Longest streak", dayCount(stats.longestStreak))
    }
    SettingsCard(title = "Activity") {
        WeeklyActivityStrip(stats.weeklyActivity)
        Spacer(Modifier.height(NotaSpacing.sm))
        Text(
            "Last 12 weeks",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SettingsCard {
        StatsRow("Started this week", stats.startedThisWeek.toString())
        StatsRow("Edited this week", stats.editedThisWeek.toString())
        StatsRow("Started this month", stats.startedThisMonth.toString())
        StatsRow("Edited this month", stats.editedThisMonth.toString())
    }
    stats.writingSince?.let { since ->
        Text(
            "Writing since " + since.format(
                DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()),
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (stats.lockedNotes > 0) {
        Text(
            "Locked notes aren't counted in word totals.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HeadlineStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatsRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = NotaSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The twelve-week strip: one quiet pine bar per week, height
 * proportional to that week's notes, a faint tick for an empty
 * week. No axes, no numbers on the bars — the shape is the
 * point.
 */
@Composable
private fun WeeklyActivityStrip(weekly: List<Int>) {
    val maxCount = (weekly.maxOrNull() ?: 0).coerceAtLeast(1)
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        weekly.forEach { count ->
            val fraction = count.toFloat() / maxCount
            Box(
                Modifier
                    .weight(1f)
                    .height(if (count == 0) 4.dp else (10 + 34 * fraction).dp)
                    .background(
                        if (count == 0) {
                            MaterialTheme.colorScheme.outlineVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                                .copy(alpha = 0.35f + 0.65f * fraction)
                        },
                        RoundedCornerShape(3.dp),
                    ),
            )
        }
    }
}

/** The stats load's state: loading, loaded, or failed — a
 *  failure is shown, never swallowed into a blank screen. */
private sealed interface LoadState {
    data object Loading : LoadState
    data class Loaded(val stats: WritingStats) : LoadState
    data object Failed : LoadState
}

/** Locale-proof grouping, like the Writing typography stamp. */
private fun formatCount(value: Int): String =
    String.format(Locale.US, "%,d", value)

private fun dayCount(days: Int): String =
    if (days == 1) "1 day" else "$days days"
