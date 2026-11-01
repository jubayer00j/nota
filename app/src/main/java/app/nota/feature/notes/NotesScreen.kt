package app.nota.feature.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.domain.SmartFolderKey
import app.nota.ui.components.NoteCard
import app.nota.ui.theme.NotaSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private val FAB_CLEARANCE = 88.dp

private fun formatUpdated(updatedAt: Long): String =
    SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(updatedAt))

private fun emptyText(filter: NotesFilter): String = when (filter) {
    NotesFilter.All -> "No notes yet. Tap + to write your first note."
    is NotesFilter.Folder -> "No notes in this folder yet."
    is NotesFilter.Tag -> "No notes with this tag yet."
    is NotesFilter.Smart -> "Nothing matches this smart folder yet."
    NotesFilter.Favourites -> "No favourites yet. Star a note to see it here."
    NotesFilter.Archived -> "No archived notes."
    NotesFilter.Trash -> "Trash is empty."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    onOpenNote: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    viewModel: NotesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showFoldersDialog by remember { mutableStateOf(false) }

    fun swipeWithUndo(note: NoteEntity, message: String, action: () -> Unit) {
        action()
        scope.launch {
            val result = snackbarHostState.showSnackbar(message = message, actionLabel = "Undo")
            if (result == SnackbarResult.ActionPerformed) viewModel.upsert(note)
        }
    }

    // Permanent delete CASCADEs away tags/attachments/reminders/versions,
    // so an Undo that re-upserts the bare note row would resurrect a
    // stripped note and pretend the data came back. No Undo here
    // (re-review fix) — the message says plainly that it is permanent.
    fun swipeNoUndo(message: String, action: () -> Unit) {
        action()
        scope.launch {
            snackbarHostState.showSnackbar(message = message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.selection.isNotEmpty()) {
                TopAppBar(
                    title = { Text("${state.selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    actions = {
                        if (state.filter is NotesFilter.Trash || state.filter is NotesFilter.Archived) {
                            IconButton(onClick = { viewModel.batchRestore() }) {
                                Icon(Icons.Filled.Restore, contentDescription = "Restore selected")
                            }
                        } else {
                            IconButton(onClick = { viewModel.batchPin() }) {
                                Icon(Icons.Filled.PushPin, contentDescription = "Pin selected")
                            }
                            IconButton(onClick = { viewModel.batchArchive() }) {
                                Icon(Icons.Filled.Archive, contentDescription = "Archive selected")
                            }
                        }
                        if (state.filter is NotesFilter.Trash) {
                            IconButton(onClick = { viewModel.batchDelete() }) {
                                Icon(Icons.Filled.DeleteForever, contentDescription = "Delete selected permanently")
                            }
                        } else {
                            IconButton(onClick = { viewModel.batchTrash() }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Trash selected")
                            }
                        }
                    },
                )
            } else {
                var sortExpanded by remember { mutableStateOf(false) }
                var overflowExpanded by remember { mutableStateOf(false) }
                TopAppBar(
                    title = { Text("Nota", style = MaterialTheme.typography.headlineMedium) },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, contentDescription = "Search")
                        }
                        Box {
                            IconButton(onClick = { sortExpanded = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text((if (state.sort == order) "✓ " else "") + order.label) },
                                        onClick = { viewModel.setSort(order); sortExpanded = false },
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { overflowExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = overflowExpanded, onDismissRequest = { overflowExpanded = false }) {
                                DropdownMenuItem(
                                    text = { Text("All notes") },
                                    onClick = { viewModel.setFilter(NotesFilter.All); overflowExpanded = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Archived") },
                                    onClick = { viewModel.setFilter(NotesFilter.Archived); overflowExpanded = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Trash") },
                                    onClick = { viewModel.setFilter(NotesFilter.Trash); overflowExpanded = false },
                                )
                                DropdownMenuItem(
                                    text = { Text("Manage folders") },
                                    onClick = { showFoldersDialog = true; overflowExpanded = false },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (state.selection.isEmpty()) {
                FloatingActionButton(onClick = { viewModel.createNote(onCreated = onOpenNote) }) {
                    Icon(Icons.Filled.Add, contentDescription = "New note")
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = NotaSpacing.md, vertical = NotaSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
            ) {
                item {
                    FilterChip(
                        selected = state.filter is NotesFilter.All,
                        onClick = { viewModel.setFilter(NotesFilter.All) },
                        label = { Text("All") },
                    )
                }
                item {
                    FilterChip(
                        selected = state.filter is NotesFilter.Favourites,
                        onClick = { viewModel.setFilter(NotesFilter.Favourites) },
                        label = { Text("Favourites") },
                    )
                }
                items(SmartFolderKey.entries.toList()) { key ->
                    FilterChip(
                        selected = (state.filter as? NotesFilter.Smart)?.key == key,
                        onClick = { viewModel.setFilter(NotesFilter.Smart(key)) },
                        label = { Text(key.label) },
                    )
                }
                items(state.folders) { folder ->
                    FilterChip(
                        selected = (state.filter as? NotesFilter.Folder)?.id == folder.id,
                        onClick = { viewModel.setFilter(NotesFilter.Folder(folder.id)) },
                        label = { Text(folder.name) },
                        leadingIcon = {
                            Box(
                                Modifier.size(10.dp).clip(CircleShape)
                                    .background(Color(folder.colorArgb ?: 0xFF5B7553.toInt())),
                            )
                        },
                    )
                }
                items(state.tags) { tag ->
                    FilterChip(
                        selected = (state.filter as? NotesFilter.Tag)?.id == tag.id,
                        onClick = { viewModel.setFilter(NotesFilter.Tag(tag.id)) },
                        label = { Text("#${tag.name}") },
                    )
                }
            }
            if (state.items.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(NotaSpacing.lg), contentAlignment = Alignment.Center) {
                    Text(
                        emptyText(state.filter),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = NotaSpacing.md, end = NotaSpacing.md,
                        top = NotaSpacing.sm, bottom = FAB_CLEARANCE,
                    ),
                    verticalArrangement = Arrangement.spacedBy(NotaSpacing.md),
                ) {
                    items(state.items, key = { it.note.id }) { item ->
                        val note = item.note
                        val inTrash = state.filter is NotesFilter.Trash
                        val inArchived = state.filter is NotesFilter.Archived
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                when (value) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        if (inTrash || inArchived) {
                                            swipeWithUndo(note, "Note restored") { viewModel.restore(note) }
                                        } else {
                                            swipeWithUndo(note, "Note archived") { viewModel.archive(note) }
                                        }
                                        true
                                    }
                                    SwipeToDismissBoxValue.EndToStart -> {
                                        if (inTrash) {
                                            swipeNoUndo("Note deleted permanently") { viewModel.deletePermanently(note) }
                                        } else {
                                            swipeWithUndo(note, "Note moved to trash") { viewModel.trash(note) }
                                        }
                                        true
                                    }
                                    SwipeToDismissBoxValue.Settled -> false
                                }
                            },
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            backgroundContent = {
                                val isStart = dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd
                                Box(
                                    Modifier.fillMaxSize()
                                        .background(
                                            if (isStart) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.errorContainer,
                                        )
                                        .padding(horizontal = NotaSpacing.md),
                                    contentAlignment = if (isStart) Alignment.CenterStart else Alignment.CenterEnd,
                                ) {
                                    Icon(
                                        if (isStart) {
                                            if (inTrash || inArchived) Icons.Filled.Restore else Icons.Filled.Archive
                                        } else {
                                            if (inTrash) Icons.Filled.DeleteForever else Icons.Filled.Delete
                                        },
                                        contentDescription = null,
                                    )
                                }
                            },
                        ) {
                            NoteCard(
                                title = note.title,
                                snippet = note.plainText,
                                updatedLabel = formatUpdated(note.updatedAt),
                                folderName = item.folderName,
                                tagNames = item.tagNames,
                                pinned = note.pinned,
                                favourite = note.favourite,
                                coverImagePath = item.coverImagePath,
                                selected = note.id in state.selection,
                                onClick = {
                                    if (state.selection.isNotEmpty()) viewModel.toggleSelect(note.id)
                                    else onOpenNote(note.id)
                                },
                                onLongClick = { viewModel.toggleSelect(note.id) },
                            )
                        }
                    }
                }
            }
        }
    }
    if (showFoldersDialog) {
        ManageFoldersDialog(
            folders = state.folders,
            onCreate = { name, color -> viewModel.createFolder(name, color) },
            onRename = { folder, name -> viewModel.renameFolder(folder, name) },
            onSetColor = { folder, color -> viewModel.setFolderColor(folder, color) },
            onDelete = { viewModel.deleteFolder(it) },
            onDismiss = { showFoldersDialog = false },
        )
    }
}

/** Preset folder cover colours (spec §12 folder covers). */
private val FOLDER_COLORS: List<Int> = listOf(
    0xFF5B7553.toInt(), 0xFFB5654A.toInt(), 0xFF4A6FA5.toInt(), 0xFFC2A14D.toInt(),
    0xFF7A5FA0.toInt(), 0xFF4E7E79.toInt(), 0xFF9C6644.toInt(), 0xFF6B7280.toInt(),
)

@Composable
private fun FolderColorRow(selected: Int?, onSelect: (Int?) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = NotaSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Colour", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.size(22.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { onSelect(null) },
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", style = MaterialTheme.typography.labelSmall)
        }
        FOLDER_COLORS.forEach { color ->
            Box(
                Modifier.size(22.dp).clip(CircleShape)
                    .background(Color(color))
                    .then(
                        if (selected == color) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
private fun ManageFoldersDialog(
    folders: List<FolderEntity>,
    onCreate: (String, Int?) -> Unit,
    onRename: (FolderEntity, String) -> Unit,
    onSetColor: (FolderEntity, Int?) -> Unit,
    onDelete: (FolderEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf<Int?>(null) }
    var editing by remember { mutableStateOf<FolderEntity?>(null) }
    var editName by remember { mutableStateOf("") }
    var editColor by remember { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage folders") },
        text = {
            Column {
                if (folders.isEmpty()) {
                    Text("No folders yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                folders.forEach { folder ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(Color(folder.colorArgb ?: 0xFF5B7553.toInt())),
                        )
                        Spacer(Modifier.size(NotaSpacing.sm))
                        Text(folder.name, Modifier.weight(1f))
                        TextButton(onClick = {
                            editing = folder; editName = folder.name; editColor = folder.colorArgb
                        }) { Text("Rename") }
                        IconButton(onClick = { onDelete(folder) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${folder.name}")
                        }
                    }
                }
                Spacer(Modifier.height(NotaSpacing.sm))
                if (editing != null) {
                    OutlinedTextField(
                        value = editName, onValueChange = { editName = it },
                        label = { Text("Folder name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FolderColorRow(selected = editColor, onSelect = { editColor = it })
                    TextButton(
                        onClick = {
                            editing?.let { onRename(it, editName); onSetColor(it, editColor) }
                            editing = null
                        },
                    ) { Text("Save rename") }
                } else {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text("New folder") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FolderColorRow(selected = newColor, onSelect = { newColor = it })
                    TextButton(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onCreate(newName, newColor); newName = ""; newColor = null
                            }
                        },
                    ) { Text("Add folder") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
