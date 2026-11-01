package app.nota.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.nota.ui.theme.NotaEditorBody
import app.nota.ui.theme.NotaSpacing
import org.koin.androidx.compose.koinViewModel

/**
 * Plain-text editor (Phase 1b) — route `note/{noteId}`. Title, serif body,
 * folder dropdown and comma-separated tags. Saves on the ✓ action AND on
 * back when dirty. PR-C replaces the body field with the rich editor;
 * the route and this signature stay stable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlainNoteEditorScreen(
    noteId: String,
    onBack: () -> Unit = {},
    viewModel: PlainNoteEditorViewModel = koinViewModel(),
) {
    LaunchedEffect(noteId) { viewModel.load(noteId) }
    val note by viewModel.currentNote.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val existingTags by viewModel.tagNames.collectAsState()
    val tagsLoaded by viewModel.tagsLoaded.collectAsState()

    var initializedFor by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var tagsCsv by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf<String?>(null) }
    var savedSnapshot by remember { mutableStateOf<List<String?>>(emptyList()) }
    var savedTagsCsv by remember { mutableStateOf<String?>(null) }

    val current = note
    if (current != null && initializedFor != current.id) {
        initializedFor = current.id
        title = current.title
        body = viewModel.bodyText(current)
        folderId = current.folderId
    }
    // Tags arrive on their own stream. Only once they have LOADED do we
    // know the persisted value: record it as the tag snapshot and seed the
    // field — without clobbering text the user already typed. Snapshotting
    // or saving tag state before load would read the not-yet-seeded empty
    // field as "all tags removed" and silently wipe them (re-review fix).
    if (tagsLoaded && savedTagsCsv == null) {
        val loaded = existingTags.joinToString(", ")
        savedTagsCsv = loaded
        if (tagsCsv.isEmpty()) tagsCsv = loaded
    }
    if (current != null && savedSnapshot.isEmpty()) {
        savedSnapshot = listOf(title, body, folderId)
    }
    val fieldsDirty = current != null && savedSnapshot.isNotEmpty() &&
        listOf(title, body, folderId) != savedSnapshot
    val tagsDirty = savedTagsCsv != null && tagsCsv != savedTagsCsv
    val dirty = fieldsDirty || tagsDirty

    fun saveIfDirty(then: () -> Unit) {
        if (dirty) {
            // Tags not loaded yet -> null tells the VM to leave them alone.
            viewModel.save(title, body, folderId, if (tagsLoaded) tagsCsv else null) {
                savedSnapshot = listOf(title, body, folderId)
                if (tagsLoaded) savedTagsCsv = tagsCsv
                then()
            }
        } else {
            then()
        }
    }

    BackHandler { saveIfDirty(onBack) }

    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = {
                    IconButton(onClick = { saveIfDirty(onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { saveIfDirty(onBack) }) {
                        Icon(Icons.Filled.Check, contentDescription = "Save and done")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(if (current?.pinned == true) "Unpin" else "Pin") },
                            leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                            onClick = { viewModel.togglePinned(); menuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text(if (current?.favourite == true) "Remove from favourites" else "Add to favourites") },
                            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                            onClick = { viewModel.toggleFavourite(); menuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Archive") },
                            leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                            onClick = { menuExpanded = false; saveIfDirty { viewModel.archive(onBack) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to trash") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuExpanded = false; saveIfDirty { viewModel.delete(onBack) } },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) {
            Column(Modifier.fillMaxSize().padding(padding).padding(NotaSpacing.lg)) {
                Text("Loading note…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Scaffold
        }
        if (current.locked) {
            // Phase 2b defence-in-depth: the plain editor is not routed
            // in the nav graph today (the rich editor owns the route),
            // but if it is ever reached with a locked note it must show
            // the lock state, never the (sealed) body.
            Column(Modifier.fillMaxSize().padding(padding).padding(NotaSpacing.lg)) {
                Text("This note is locked", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Unlock it from the note editor to read or change it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(NotaSpacing.md),
        ) {
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Title") },
                textStyle = MaterialTheme.typography.titleLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            NoteMetaRow(
                folders = folders,
                folderId = folderId,
                onFolderSelected = { folderId = it },
                tags = parseTagsCsv(tagsCsv),
                onTagsChanged = { tagsCsv = it.joinToString(", ") },
                modifier = Modifier.padding(vertical = NotaSpacing.xs),
            )
            val richBody = viewModel.isRichBody(current)
            if (richBody) {
                Text(
                    "This note uses rich formatting — its body is protected in " +
                        "the plain editor and opens fully in the rich editor.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = NotaSpacing.sm),
                )
            }
            TextField(
                value = body,
                onValueChange = { body = it },
                readOnly = richBody,
                placeholder = { Text("Write…") },
                // Phase 2a rider 1 audit: pin the body ink to onSurface
                // explicitly instead of relying on the field's default
                // text colour merging (same Unspecified-colour hazard as
                // the rich editor, even though Material3 fields resolve
                // it to the content colour today).
                textStyle = NotaEditorBody.copy(color = MaterialTheme.colorScheme.onSurface),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
                minLines = 12,
            )
        }
    }
}
