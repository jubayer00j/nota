package app.nota.feature.notes

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.nota.data.db.FolderEntity
import app.nota.data.db.NoteEntity
import app.nota.domain.SmartFolderKey
import app.nota.notes.R
import app.nota.ui.components.NoteCard
import app.nota.ui.theme.NotaSpacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
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
    val swipePrefs by viewModel.swipePreferences.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var showFoldersDialog by remember { mutableStateOf(false) }

    // Phase 3.5 (D1): a swiped note's row leaves the composition at
    // COMMIT time, not whenever the database round-trip lands. The
    // pre-3.5 list removed a row only when the observed Room flow
    // re-emitted; any stall in that path (the flow bound to a
    // superseded database after an account switch — see
    // NotesViewModel) left the dismissed item composed forever at
    // its dismissed offset: a background-only, still-tappable ghost
    // row. Ids enter [dismissedIds] the moment their swipe commits
    // and are rendered out of the list from that frame on; the undo
    // batch resolution lifts exactly the batch's ids (see
    // [swipeWithUndo]), and the pruning effect drops ids the data no
    // longer contains (a dismissal the data has confirmed needs no
    // further guarding).
    val dismissedIds = remember { mutableStateListOf<String>() }
    LaunchedEffect(state.items) {
        val liveIds = state.items.mapTo(HashSet()) { it.note.id }
        dismissedIds.removeAll(dismissedIds.filter { it !in liveIds })
    }
    val visibleItems = state.items.filter { it.note.id !in dismissedIds }

    // Phase 2.9 (J3): undo is BATCHED. A single SnackbarHostState
    // carries ONE snackbar: a second swipe dismisses the first
    // snackbar, whose showSnackbar then returns Dismissed — so the
    // old per-swipe coroutine silently lost every earlier note's
    // Undo (swipe-archive one note, swipe-delete another, tap Undo:
    // only the delete came back; the archived note stayed gone).
    // The repository round-trip itself is exact (pinned by
    // SwipeUndoFidelityRobolectricTest) — the loss was here.
    //
    // Now every snapshot taken while an undo window is live queues
    // in [pendingUndo] (added BEFORE the previous snackbar job is
    // cancelled, so no interleaving can drop one), and the single
    // surviving snackbar coroutine resolves the whole batch.
    // Resolution applies the snapshots in REVERSE swipe order
    // (Codex, PR #14): the same note can be swiped twice in one
    // window (archive it, then restore it from Archived), and only
    // reverse order leaves each note at its EARLIEST snapshot —
    // the state before the whole batch, which is what Undo means.
    // Dismiss/timeout just clears the queue.
    val pendingUndo = remember { mutableStateListOf<NoteEntity>() }
    var undoJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Phase 2.9 batching (unchanged) + Phase 3.5 optimistic exclusion:
     * when [exclude] is true the note's row unmounts in the same frame
     * (see [dismissedIds]); Pin passes false because the note stays in
     * the list — only its pinned flag (and sort position) changes.
     * Resolution lifts exactly this batch's exclusions; a cancelled
     * job (a newer swipe took the window over) never runs its
     * resolution block, so no batch can drop another's ids.
     */
    fun swipeWithUndo(note: NoteEntity, message: String, exclude: Boolean = true, action: () -> Unit) {
        action()
        pendingUndo += note
        if (exclude) dismissedIds += note.id
        undoJob?.cancel()
        undoJob = scope.launch {
            val result = snackbarHostState.showSnackbar(message = message, actionLabel = "Undo")
            val batchIds = pendingUndo.map { it.id }
            if (result == SnackbarResult.ActionPerformed) {
                pendingUndo.asReversed().forEach { viewModel.upsert(it) }
            }
            pendingUndo.clear()
            dismissedIds.removeAll(batchIds)
        }
    }

    // Permanent delete CASCADEs away tags/attachments/reminders/versions,
    // so an Undo that re-upserts the bare note row would resurrect a
    // stripped note and pretend the data came back. No Undo here
    // (re-review fix) — the message says plainly that it is permanent.
    // Phase 3.5: the row still leaves at commit via [dismissedIds].
    fun swipeNoUndo(note: NoteEntity, message: String, action: () -> Unit) {
        action()
        dismissedIds += note.id
        scope.launch {
            snackbarHostState.showSnackbar(message = message)
        }
    }

    /** Performs a resolved swipe behaviour (Phase 3.5, D2/D3). */
    fun performSwipe(kind: SwipeKind, note: NoteEntity) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        when (kind) {
            SwipeKind.ARCHIVE ->
                swipeWithUndo(note, "Note archived") { viewModel.archive(note) }
            SwipeKind.TRASH ->
                swipeWithUndo(note, "Note moved to trash") { viewModel.trash(note) }
            SwipeKind.RESTORE ->
                swipeWithUndo(note, "Note restored") { viewModel.restore(note) }
            SwipeKind.PIN ->
                swipeWithUndo(
                    note,
                    if (note.pinned) "Note unpinned" else "Note pinned",
                    exclude = false,
                ) { viewModel.setPinned(note, !note.pinned) }
            SwipeKind.DELETE_FOREVER ->
                swipeNoUndo(note, "Note deleted permanently") { viewModel.deletePermanently(note) }
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
                    title = {
                        // Brand lockup (Phase 2a rider): the N mark +
                        // wordmark, replacing the bare text title. The
                        // mark is a plain vector drawable (API 26-36
                        // safe); actions/sort menu are untouched, as is
                        // the selection-mode bar above.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painterResource(R.drawable.ic_nota_mark),
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                            )
                            Spacer(Modifier.size(NotaSpacing.sm))
                            Text("Nota", style = MaterialTheme.typography.headlineMedium)
                        }
                    },
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
            if (visibleItems.isEmpty()) {
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
                    items(visibleItems, key = { it.note.id }) { item ->
                        val note = item.note
                        val startKind = resolveSwipeKind(state.filter, SwipeDirection.RIGHT, swipePrefs)
                        val endKind = resolveSwipeKind(state.filter, SwipeDirection.LEFT, swipePrefs)
                        // Phase 3.5 (D1): the dismiss state is a plain
                        // remember keyed by note id — deliberately NOT
                        // rememberSaveable. The saveable variant
                        // restores a dismissed value when an item with
                        // the same key re-enters the list (an undone
                        // note, a filter round-trip), recomposing it
                        // already dismissed: another ghost-row path. A
                        // disposed row now forgets its state, so a
                        // returning note always starts Settled.
                        val dismissState = remember(note.id) {
                            SwipeToDismissBoxState(
                                initialValue = SwipeToDismissBoxValue.Settled,
                                positionalThreshold = { total -> total * SWIPE_COMMIT_FRACTION },
                            )
                        }
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = startKind != null,
                            enableDismissFromEndToStart = endKind != null,
                            onDismiss = { value ->
                                val kind = when (value) {
                                    SwipeToDismissBoxValue.StartToEnd -> startKind
                                    SwipeToDismissBoxValue.EndToStart -> endKind
                                    SwipeToDismissBoxValue.Settled -> null
                                }
                                if (kind != null) performSwipe(kind, note)
                            },
                            backgroundContent = {
                                SwipeReveal(
                                    targetValue = dismissState.targetValue,
                                    startKind = startKind,
                                    endKind = endKind,
                                    pinned = note.pinned,
                                )
                            },
                        ) {
                            NoteCard(
                                title = note.title,
                                snippet = if (note.locked) "" else note.plainText,
                                updatedLabel = formatUpdated(note.updatedAt),
                                folderName = item.folderName,
                                tagNames = item.tagNames,
                                pinned = note.pinned,
                                favourite = note.favourite,
                                coverImagePath = if (note.locked) null else item.coverImagePath,
                                locked = note.locked,
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

/**
 * The fraction of a row's width a swipe must cross to commit (Phase
 * 3.5, D2): a short drag springs back — committing takes a deliberate
 * swipe, never an accidental nudge.
 */
private const val SWIPE_COMMIT_FRACTION = 0.35f

/**
 * Phase 3.5 (D2): the swipe reveal. It is confined to the row's own
 * bounds (the dismiss box composes its background at matchParentSize),
 * clipped to the card's corner radius, and it NAMES its action — an
 * icon plus a short label in a colour pair distinct per action,
 * aligned to the side the swipe is opening. The pre-3.5 reveal was a
 * bare icon floating in an oversized block that read as a UI error.
 */
@Composable
private fun SwipeReveal(
    targetValue: SwipeToDismissBoxValue,
    startKind: SwipeKind?,
    endKind: SwipeKind?,
    pinned: Boolean,
) {
    val kind = when (targetValue) {
        SwipeToDismissBoxValue.StartToEnd -> startKind
        SwipeToDismissBoxValue.EndToStart -> endKind
        SwipeToDismissBoxValue.Settled -> null
    }
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (kind) {
        SwipeKind.ARCHIVE -> colors.primaryContainer to colors.onPrimaryContainer
        SwipeKind.TRASH -> colors.errorContainer to colors.onErrorContainer
        SwipeKind.RESTORE -> colors.secondaryContainer to colors.onSecondaryContainer
        SwipeKind.PIN -> colors.tertiaryContainer to colors.onTertiaryContainer
        SwipeKind.DELETE_FOREVER -> colors.error to colors.onError
        null -> Color.Transparent to Color.Transparent
    }
    Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(horizontal = NotaSpacing.md),
        contentAlignment = if (targetValue == SwipeToDismissBoxValue.EndToStart) {
            Alignment.CenterEnd
        } else {
            Alignment.CenterStart
        },
    ) {
        if (kind != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when (kind) {
                        SwipeKind.ARCHIVE -> Icons.Filled.Archive
                        SwipeKind.TRASH -> Icons.Filled.Delete
                        SwipeKind.RESTORE -> Icons.Filled.Restore
                        SwipeKind.PIN -> Icons.Filled.PushPin
                        SwipeKind.DELETE_FOREVER -> Icons.Filled.DeleteForever
                    },
                    contentDescription = null,
                    tint = foreground,
                )
                Spacer(Modifier.size(NotaSpacing.sm))
                Text(
                    text = when (kind) {
                        SwipeKind.ARCHIVE -> "Archive"
                        SwipeKind.TRASH -> "Trash"
                        SwipeKind.RESTORE -> "Restore"
                        SwipeKind.PIN -> if (pinned) "Unpin" else "Pin"
                        SwipeKind.DELETE_FOREVER -> "Delete forever"
                    },
                    color = foreground,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
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
