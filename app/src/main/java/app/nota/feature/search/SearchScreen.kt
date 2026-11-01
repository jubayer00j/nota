package app.nota.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import app.nota.ui.components.NotaSecondaryButton
import app.nota.ui.components.NoteCard
import app.nota.ui.theme.NotaSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.koin.androidx.compose.koinViewModel

/** Search (Phase 1b): FTS results as note cards + saved searches. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onOpenNote: (String) -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: SearchViewModel = koinViewModel(),
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val saved by viewModel.savedSearches.collectAsState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(NotaSpacing.md)) {
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setQuery(it) },
                label = { Text("Search notes") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            if (saved.isNotEmpty()) {
                Spacer(Modifier.height(NotaSpacing.sm))
                Text("Saved searches", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(NotaSpacing.xs),
                ) {
                    saved.forEach { search ->
                        InputChip(
                            selected = false,
                            onClick = { viewModel.applySaved(search) },
                            label = { Text(search.name) },
                            trailingIcon = {
                                IconButton(onClick = { viewModel.deleteSaved(search) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Delete ${search.name}")
                                }
                            },
                        )
                    }
                }
            }
            if (query.isNotBlank()) {
                Spacer(Modifier.height(NotaSpacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${results.size} result(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    NotaSecondaryButton(text = "Save this search", onClick = { showSaveDialog = true })
                }
                Spacer(Modifier.height(NotaSpacing.sm))
                if (results.isEmpty()) {
                    Text(
                        "No notes match \"$query\".",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = NotaSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(NotaSpacing.md),
                    ) {
                        items(results, key = { it.id }) { note ->
                            NoteCard(
                                title = note.title,
                                snippet = if (note.locked) "" else note.plainText,
                                updatedLabel = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(note.updatedAt)),
                                pinned = note.pinned,
                                favourite = note.favourite,
                                locked = note.locked,
                                onClick = { onOpenNote(note.id) },
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(NotaSpacing.md))
                Text(
                    "Type to search titles and body text. Save a search to re-run it later.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save this search") },
            text = {
                OutlinedTextField(
                    value = saveName, onValueChange = { saveName = it },
                    label = { Text("Name") }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveCurrentSearch(saveName)
                        saveName = ""
                        showSaveDialog = false
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
            },
        )
    }
}
