package app.nota.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import app.nota.ui.components.NoteCard
import app.nota.ui.theme.NotaSpacing
import org.koin.androidx.compose.koinViewModel

/** Design-preview data so the Phase 0 shell shows the design language with
 *  realistic notes (including Bengali). Replaced by Room data in Phase 1. */
private data class PreviewNote(
    val title: String,
    val snippet: String,
    val updated: String,
    val folder: String?,
    val tags: List<String>,
    val pinned: Boolean = false,
    val favourite: Boolean = false,
)

private val PreviewNotes = listOf(
    PreviewNote(
        title = "আজকের পরিকল্পনা",
        snippet = "সকালে বাজার, তারপর পড়া।\n• Physics — chapter 4\n• Higher Math — integration",
        updated = "Today, 9:12 AM",
        folder = "Daily",
        tags = listOf("plan"),
        pinned = true,
    ),
    PreviewNote(
        title = "Palashi — scene notes",
        snippet = "The river at dawn. Mir Jafar's tent should stay in shadow until the reveal…",
        updated = "Yesterday",
        folder = "Projects",
        tags = listOf("palashi", "film"),
        favourite = true,
    ),
    PreviewNote(
        title = "Shopping list",
        snippet = "Rice, lentils, eggs, milk, green chilli",
        updated = "Mon, 6:40 PM",
        folder = null,
        tags = listOf("home"),
    ),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(viewModel: NotesViewModel = koinViewModel()) {
    val dbNotes by viewModel.notes.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nota", style = MaterialTheme.typography.headlineMedium) },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* Phase 1: new note */ }) {
                Icon(Icons.Filled.Add, contentDescription = "New note")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(NotaSpacing.md),
            verticalArrangement = Arrangement.spacedBy(NotaSpacing.md),
        ) {
            item {
                Column {
                    Text("Today", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(NotaSpacing.xs))
                    Text(
                        "Your daily note and what's due — the Today surface (§12) lands in Phase 1. " +
                            "Below: design-preview cards (${dbNotes.size} notes in the local database).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(PreviewNotes) { note ->
                NoteCard(
                    title = note.title,
                    snippet = note.snippet,
                    updatedLabel = note.updated,
                    folderName = note.folder,
                    tagNames = note.tags,
                    pinned = note.pinned,
                    favourite = note.favourite,
                )
            }
        }
    }
}
