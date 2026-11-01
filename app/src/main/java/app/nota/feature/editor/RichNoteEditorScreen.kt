package app.nota.feature.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import app.nota.data.db.AttachmentEntity
import app.nota.data.html.CodeSegment
import app.nota.data.html.ChartSegment
import app.nota.data.html.DividerSegment
import app.nota.data.html.DocSegment
import app.nota.data.html.ImageSegment
import app.nota.data.html.NoteDocument
import app.nota.data.html.OpaqueSegment
import app.nota.data.html.OutlineEntry
import app.nota.data.html.QuoteSegment
import app.nota.data.html.TableCellSpan
import app.nota.data.html.TableSegment
import app.nota.data.html.TaskItem
import app.nota.data.html.TaskListSegment
import app.nota.data.html.TextSegment
import app.nota.data.text.PlainTextCodec
import app.nota.ui.theme.NotaEditorBody
import app.nota.ui.theme.NotaSpacing
import coil3.compose.AsyncImage
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.roundToInt
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** One row of the editor's working document (stable id across recompositions). */
private data class UiSegment(val id: String, val segment: DocSegment)

private fun newUiId(): String = UUID.randomUUID().toString()

private fun escapeCellText(text: String): String = text
    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/**
 * The Phase 1c rich editor — route `note/{noteId}` (unchanged from 1b).
 *
 * The stored HTML is split by [NoteDocument] (the SPIKE-REPORT guard):
 * text-shaped runs are edited with Compose Rich Editor states; tables,
 * task lists, images, charts, quotes, code blocks and dividers are block
 * islands with their own small editors, so nothing the library would
 * mangle ever passes through it. Autosave persists through the VM every
 * ~1.5 s when dirty; the app bar shows the save state. Focus mode fades
 * the chrome while typing; the outline sheet jumps by heading; [[
 * autocomplete inserts literal [[Title]] text (1.x-compatible), resolved
 * into backlink edges by the repository on save.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalRichTextApi::class)
@Composable
fun RichNoteEditorScreen(
    noteId: String,
    onBack: () -> Unit = {},
    onOpenNote: (String) -> Unit = {},
    viewModel: RichNoteEditorViewModel = koinViewModel(),
) {
    LaunchedEffect(noteId) { viewModel.load(noteId) }
    val note by viewModel.note.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val existingTags by viewModel.tagNames.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val linkedFrom by viewModel.linkedFrom.collectAsState()
    val linkableNotes by viewModel.linkableNotes.collectAsState()
    val saveState by viewModel.saveState.collectAsState()

    var initializedFor by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var tagsCsv by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf<String?>(null) }
    var segments by remember { mutableStateOf<List<UiSegment>>(emptyList()) }
    val textStates = remember { mutableMapOf<String, RichTextState>() }
    var activeSegmentId by remember { mutableStateOf<String?>(null) }
    var focusMode by remember { mutableStateOf(false) }
    var docStats by remember { mutableStateOf("") }

    var menuExpanded by remember { mutableStateOf(false) }
    var insertMenuExpanded by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }
    var showOutline by remember { mutableStateOf(false) }
    var outlineItems by remember { mutableStateOf<List<OutlineEntry>>(emptyList()) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var templateName by remember { mutableStateOf("") }
    var insertImageOnPick by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bringIntoView = remember { BringIntoViewRequester() }
    // Phase 1.5 fix 2: focus target for "tap below the last block".
    val trailingFocusRequester = remember { FocusRequester() }

    val current = note
    if (current != null && initializedFor != current.id) {
        initializedFor = current.id
        title = current.title
        folderId = current.folderId
        segments = NoteDocument.ensureTrailingTextSegment(
            NoteDocument.parse(current.contentHtml),
        ).map { UiSegment(newUiId(), it) }
    }
    var tagsSeeded by remember { mutableStateOf(false) }
    if (!tagsSeeded && existingTags.isNotEmpty()) {
        tagsCsv = existingTags.joinToString(", ")
        tagsSeeded = true
    }

    fun richStateFor(ui: UiSegment): RichTextState {
        textStates[ui.id]?.let { return it }
        val state = RichTextState()
        when (val s = ui.segment) {
            is TextSegment -> state.setHtml(s.html)
            is QuoteSegment -> state.setHtml(s.innerHtml)
            else -> Unit
        }
        textStates[ui.id] = state
        return state
    }

    fun syncSegments(): List<DocSegment> = segments.map { ui ->
        when (val s = ui.segment) {
            is TextSegment -> TextSegment(textStates[ui.id]?.toHtml() ?: s.html)
            is QuoteSegment -> QuoteSegment(textStates[ui.id]?.toHtml() ?: s.innerHtml)
            else -> s
        }
    }

    fun persistNow(then: () -> Unit = {}) {
        viewModel.persist(title, syncSegments(), folderId, tagsCsv, then)
    }

    fun updateBlock(id: String, segment: DocSegment) {
        segments = segments.map { if (it.id == id) it.copy(segment = segment) else it }
    }

    /**
     * Phase 1.5 fix 2: the working document always ends with a text
     * segment to hold a caret below the last block. Re-applied after
     * structural edits only (insert/remove), never per keystroke.
     */
    fun ensureTrailing(list: List<UiSegment>): List<UiSegment> = when {
        list.isEmpty() -> listOf(UiSegment(newUiId(), TextSegment("")))
        list.last().segment is TextSegment -> list
        else -> list + UiSegment(newUiId(), TextSegment(""))
    }

    fun removeBlock(id: String) {
        segments = ensureTrailing(segments.filterNot { it.id == id })
    }

    fun insertBlock(segment: DocSegment) {
        val ui = UiSegment(newUiId(), segment)
        val activeIndex = segments.indexOfFirst { it.id == activeSegmentId }
        segments = ensureTrailing(
            if (activeIndex >= 0) {
                segments.toMutableList().apply { add(activeIndex + 1, ui) }
            } else {
                segments + ui
            },
        )
    }

    val activeState: RichTextState? = activeSegmentId?.let { textStates[it] }

    // Dictation seam (Phase 5): the active editor's insert-at-caret.
    LaunchedEffect(activeSegmentId) {
        viewModel.textInserter = { text ->
            textStates[activeSegmentId]?.let { st -> st.replaceTextRange(st.selection, text) }
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.textInserter = null }
    }

    // Autosave tick + stats refresh.
    LaunchedEffect(initializedFor) {
        if (initializedFor == null) return@LaunchedEffect
        while (true) {
            delay(1500)
            if (segments.isNotEmpty()) {
                val plain = NoteDocument.plainText(syncSegments())
                val words = plain.split(Regex("\\s+")).count { it.isNotEmpty() }
                val minutes = maxOf(1, (words + 199) / 200)
                docStats = "$words words · $minutes min read"
                persistNow()
            }
        }
    }

    // Typewriter scrolling: keep the active segment in view as the caret moves.
    LaunchedEffect(activeState?.selection) {
        if (activeSegmentId != null) {
            runCatching { bringIntoView.bringIntoView() }
        }
    }

    BackHandler { persistNow(onBack) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.importImage(uri) { attachment ->
                if (attachment != null && insertImageOnPick) {
                    insertBlock(
                        ImageSegment(
                            NoteDocument.ATTACHMENT_SRC_PREFIX + attachment.id,
                            attachment.fileName,
                        ),
                    )
                }
                insertImageOnPick = false
            }
        } else {
            insertImageOnPick = false
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) viewModel.importFile(uri) {}
    }

    // [[ backlink autocomplete state, derived from the active editor.
    val linkTrigger: Pair<Int, String>? = run {
        val st = activeState
        if (st == null || !st.selection.collapsed) {
            null
        } else {
            val text = st.annotatedString.text
            val caret = st.selection.end.coerceIn(0, text.length)
            val match = Regex("""\[\[([^\[\]\n]*)$""").find(text.substring(0, caret))
            if (match != null) match.range.first to match.groupValues[1] else null
        }
    }
    val linkSuggestions = if (linkTrigger != null) {
        linkableNotes.filter {
            it.id != noteId && it.title.contains(linkTrigger.second, ignoreCase = true)
        }.take(6)
    } else {
        emptyList()
    }

    val chromeAlpha = if (focusMode && activeSegmentId != null) 0.35f else 1f
    val saveLabel = when (val s = saveState) {
        SaveState.Editing -> "Editing…"
        SaveState.Saving -> "Saving…"
        is SaveState.Saved -> if (s.atMillis > 0) {
            "Saved " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(s.atMillis))
        } else {
            "Saved"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.alpha(chromeAlpha),
                title = {
                    Column {
                        Text("Note")
                        Text(
                            saveLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { persistNow(onBack) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { persistNow(onBack) }) {
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
                            text = { Text("Outline") },
                            onClick = {
                                outlineItems = NoteDocument.headings(syncSegments())
                                showOutline = true
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (focusMode) "Focus mode: on" else "Focus mode: off") },
                            onClick = { focusMode = !focusMode; menuExpanded = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Save as template") },
                            onClick = {
                                templateName = title.ifBlank { "Untitled template" }
                                showTemplateDialog = true
                                menuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Archive") },
                            leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                            onClick = { menuExpanded = false; persistNow { viewModel.archive(onBack) } },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to trash") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menuExpanded = false; persistNow { viewModel.delete(onBack) } },
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
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(NotaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
            ) {
                item(key = "title") {
                    Column {
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
                        if (docStats.isNotEmpty()) {
                            Text(
                                docStats,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item(key = "meta") {
                    NoteMetaRow(
                        folders = folders,
                        folderId = folderId,
                        onFolderSelected = { folderId = it },
                        tags = parseTagsCsv(tagsCsv),
                        onTagsChanged = { tagsCsv = it.joinToString(", ") },
                    )
                }
                itemsIndexed(segments, key = { _, ui -> ui.id }) { index, ui ->
                    SegmentEditor(
                        ui = ui,
                        isActive = ui.id == activeSegmentId,
                        isTrailingCaret = index == segments.lastIndex &&
                            ui.segment is TextSegment,
                        trailingFocusRequester = trailingFocusRequester,
                        stateFor = { richStateFor(ui) },
                        onFocus = { activeSegmentId = ui.id },
                        onChange = { updateBlock(ui.id, it) },
                        onRemove = { removeBlock(ui.id) },
                        attachments = attachments,
                        fileFor = { viewModel.attachmentFile(it) },
                        bringIntoView = bringIntoView,
                    )
                }
                if (segments.lastOrNull()?.segment is TextSegment) {
                    // Phase 1.5 fix 2: the empty area below the last block
                    // is a tap target that drops the caret at the very end
                    // of the document, in the trailing text segment.
                    item(key = "below-blocks") {
                        Box(
                            Modifier.fillMaxWidth().height(96.dp).clickable {
                                val ui = segments.last()
                                val state = richStateFor(ui)
                                activeSegmentId = ui.id
                                // The trailing editor is composed next to
                                // this item, but if it has just scrolled
                                // out of the LazyColumn window the
                                // requester may be unattached — never
                                // crash the tap; the editor itself (56dp
                                // min height) remains directly tappable.
                                runCatching { trailingFocusRequester.requestFocus() }
                                state.selection =
                                    TextRange(state.annotatedString.text.length)
                            },
                        )
                    }
                }
                item(key = "attachments") {
                    AttachmentsSection(
                        attachments = attachments,
                        onAddImage = {
                            imagePicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        },
                        onAddFile = { filePicker.launch(arrayOf("*/*")) },
                        onInsertImage = { attachment ->
                            insertBlock(
                                ImageSegment(
                                    NoteDocument.ATTACHMENT_SRC_PREFIX + attachment.id,
                                    attachment.fileName,
                                ),
                            )
                        },
                        onDelete = { attachment ->
                            // Drop any embedded image block for this
                            // attachment from the working segments too
                            // (Codex F7) so autosave cannot resurrect it;
                            // the VM strips it from the stored document.
                            val targetSrc =
                                NoteDocument.ATTACHMENT_SRC_PREFIX + attachment.id
                            segments = ensureTrailing(
                                segments.filterNot {
                                    (it.segment as? ImageSegment)?.src == targetSrc
                                },
                            )
                            viewModel.deleteAttachment(attachment)
                        },
                    )
                }
                if (linkedFrom.isNotEmpty()) {
                    item(key = "linked-from") {
                        Column {
                            Text(
                                "Linked from…",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = NotaSpacing.sm),
                            )
                            linkedFrom.forEach { source ->
                                TextButton(onClick = { persistNow { onOpenNote(source.id) } }) {
                                    Text(source.title.ifBlank { "Untitled note" })
                                }
                            }
                        }
                    }
                }
            }
            if (linkSuggestions.isNotEmpty() && linkTrigger != null) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = NotaSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
                ) {
                    linkSuggestions.forEach { candidate ->
                        AssistChip(
                            onClick = {
                                val st = activeState ?: return@AssistChip
                                val caret = st.selection.end.coerceIn(0, st.annotatedString.text.length)
                                st.replaceTextRange(
                                    TextRange(linkTrigger.first, caret),
                                    "[[${candidate.title}]] ",
                                )
                            },
                            label = { Text(candidate.title.ifBlank { "Untitled note" }) },
                        )
                    }
                }
            }
            EditorToolbar(
                state = activeState,
                modifier = Modifier.alpha(chromeAlpha),
                insertMenuExpanded = insertMenuExpanded,
                onInsertMenuChange = { insertMenuExpanded = it },
                onLinkRequest = { showLinkDialog = true },
                onInsertImage = {
                    insertImageOnPick = true
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onInsertFile = { filePicker.launch(arrayOf("*/*")) },
                onInsertBlock = { insertBlock(it) },
            )
        }
    }

    if (showLinkDialog) {
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text("Insert link") },
            text = {
                OutlinedTextField(
                    value = linkUrl,
                    onValueChange = { linkUrl = it },
                    label = { Text("URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val st = activeState
                    val url = linkUrl.trim()
                    if (st != null && url.isNotEmpty()) {
                        if (st.selection.collapsed) st.addLink(text = url, url = url)
                        else st.addLinkToSelection(url)
                    }
                    linkUrl = ""
                    showLinkDialog = false
                }) { Text("Insert") }
            },
            dismissButton = {
                TextButton(onClick = { showLinkDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showOutline) {
        ModalBottomSheet(
            onDismissRequest = { showOutline = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(Modifier.fillMaxWidth().padding(NotaSpacing.md)) {
                Text("Outline", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(NotaSpacing.sm))
                if (outlineItems.isEmpty()) {
                    Text(
                        "No headings in this note yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                outlineItems.forEach { entry ->
                    TextButton(
                        onClick = {
                            showOutline = false
                            scope.launch {
                                listState.animateScrollToItem(1 + entry.segmentIndex)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            entry.title,
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = ((entry.level - 1) * 16).dp),
                        )
                    }
                }
                Spacer(Modifier.height(NotaSpacing.md))
            }
        }
    }

    if (showTemplateDialog) {
        AlertDialog(
            onDismissRequest = { showTemplateDialog = false },
            title = { Text("Save as template") },
            text = {
                OutlinedTextField(
                    value = templateName,
                    onValueChange = { templateName = it },
                    label = { Text("Template name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveAsTemplate(templateName, title, syncSegments())
                    showTemplateDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showTemplateDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun SegmentEditor(
    ui: UiSegment,
    isActive: Boolean,
    isTrailingCaret: Boolean = false,
    trailingFocusRequester: FocusRequester? = null,
    stateFor: () -> RichTextState,
    onFocus: () -> Unit,
    onChange: (DocSegment) -> Unit,
    onRemove: () -> Unit,
    attachments: List<AttachmentEntity>,
    fileFor: (AttachmentEntity) -> java.io.File,
    bringIntoView: BringIntoViewRequester,
) {
    val focusModifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() }
    val activeModifier = if (isActive) Modifier.bringIntoViewRequester(bringIntoView) else Modifier
    when (val segment = ui.segment) {
        is TextSegment -> {
            val state = stateFor()
            var editorModifier = Modifier.fillMaxWidth().then(focusModifier).then(activeModifier)
            if (isTrailingCaret) {
                // Phase 1.5 fix 2: the trailing caret segment is the tap
                // target below the last block — give it a real height and
                // the shared focus requester the tap area uses.
                if (trailingFocusRequester != null) {
                    editorModifier = editorModifier.focusRequester(trailingFocusRequester)
                }
                editorModifier = editorModifier.heightIn(min = 56.dp)
            }
            BasicRichTextEditor(
                state = state,
                textStyle = NotaEditorBody,
                modifier = editorModifier,
            )
        }
        is QuoteSegment -> {
            val state = stateFor()
            Row(Modifier.fillMaxWidth().then(focusModifier)) {
                Box(
                    Modifier.width(3.dp).heightIn(min = 48.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(NotaSpacing.sm))
                BasicRichTextEditor(
                    state = state,
                    textStyle = NotaEditorBody,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        is TableSegment -> TableBlock(ui.id, segment, onChange, onRemove)
        is TaskListSegment -> TaskListBlock(ui.id, segment, onChange, onRemove)
        is ImageSegment -> ImageBlock(segment, attachments, fileFor, onChange, onRemove)
        is ChartSegment -> BlockShell(onRemove) {
            Text(
                "Chart — preserved as-is. Chart editing arrives in a later phase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is CodeSegment -> OutlinedTextField(
            value = segment.text,
            onValueChange = { onChange(CodeSegment(it)) },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth().then(focusModifier),
            minLines = 2,
        )
        DividerSegment -> Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f))
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove divider")
            }
        }
        is OpaqueSegment -> BlockShell(onRemove) {
            Text(
                "Preserved block — kept exactly as stored.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BlockShell(onRemove: () -> Unit, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(NotaSpacing.sm),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) { content() }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove block")
            }
        }
    }
}

/**
 * A single-line cell/item field with LOCAL text state. Writing back
 * through the segment on every keystroke is fine, but rendering from the
 * segment is not: the plain-text projection trims and normalises, which
 * would eat spaces while typing. Local state is keyed by block position
 * and only seeded from the stored content.
 */
@Composable
private fun EditableCellText(
    key: String,
    initial: String,
    bodyStyle: Boolean,
    modifier: Modifier = Modifier,
    onEdit: (String) -> Unit,
) {
    var text by remember(key) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onEdit(it)
        },
        singleLine = true,
        textStyle = if (bodyStyle) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.bodySmall
        },
        modifier = modifier,
    )
}

@Composable
private fun TableBlock(
    blockId: String,
    table: TableSegment,
    onChange: (DocSegment) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(NotaSpacing.sm)) {
            table.rows.forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.xs)) {
                    row.forEachIndexed { c, cell ->
                        EditableCellText(
                            // Local field state: projecting through the codec
                            // on every frame would eat spaces mid-typing.
                            key = "$blockId-$r-$c",
                            initial = PlainTextCodec.htmlToText(cell),
                            bodyStyle = false,
                            modifier = Modifier.weight(1f),
                            onEdit = { newText ->
                                val newRows = table.rows.mapIndexed { rr, rowCells ->
                                    if (rr == r) {
                                        rowCells.mapIndexed { cc, old ->
                                            if (cc == c) escapeCellText(newText) else old
                                        }
                                    } else {
                                        rowCells
                                    }
                                }
                                onChange(TableSegment(newRows, table.headerRow, table.cellSpans))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(NotaSpacing.xs))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.xs)) {
                TextButton(onClick = {
                    val cols = table.rows.firstOrNull()?.size ?: 1
                    val newSpans = if (table.cellSpans.isEmpty()) {
                        emptyList()
                    } else {
                        table.cellSpans + listOf(List(cols) { TableCellSpan() })
                    }
                    onChange(
                        TableSegment(table.rows + listOf(List(cols) { "" }), table.headerRow, newSpans),
                    )
                }) { Text("+ Row") }
                TextButton(onClick = {
                    onChange(
                        TableSegment(
                            table.rows.map { it + "" },
                            table.headerRow,
                            table.cellSpans.map { it + TableCellSpan() },
                        ),
                    )
                }) { Text("+ Col") }
                if (table.rows.size > 1) {
                    TextButton(onClick = {
                        onChange(
                            TableSegment(
                                table.rows.dropLast(1),
                                table.headerRow,
                                table.cellSpans.dropLast(1),
                            ),
                        )
                    }) { Text("− Row") }
                }
                if ((table.rows.firstOrNull()?.size ?: 1) > 1) {
                    TextButton(onClick = {
                        onChange(
                            TableSegment(
                                table.rows.map { it.dropLast(1) },
                                table.headerRow,
                                table.cellSpans.map { it.dropLast(1) },
                            ),
                        )
                    }) { Text("− Col") }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove table")
                }
            }
        }
    }
}

@Composable
private fun TaskListBlock(
    blockId: String,
    list: TaskListSegment,
    onChange: (DocSegment) -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(NotaSpacing.sm)) {
            list.items.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = item.checked,
                        onCheckedChange = { checked ->
                            onChange(
                                TaskListSegment(
                                    list.items.mapIndexed { i, old ->
                                        if (i == index) old.copy(checked = checked) else old
                                    },
                                ),
                            )
                        },
                    )
                    EditableCellText(
                        key = "$blockId-item-$index",
                        initial = PlainTextCodec.htmlToText(item.contentHtml),
                        bodyStyle = true,
                        modifier = Modifier.weight(1f),
                        onEdit = { newText ->
                            onChange(
                                TaskListSegment(
                                    list.items.mapIndexed { i, old ->
                                        if (i == index) {
                                            old.copy(contentHtml = "<p>${escapeCellText(newText)}</p>")
                                        } else {
                                            old
                                        }
                                    },
                                ),
                            )
                        },
                    )
                    IconButton(onClick = {
                        onChange(TaskListSegment(list.items.filterIndexed { i, _ -> i != index }))
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove item")
                    }
                }
            }
            Row {
                TextButton(onClick = {
                    onChange(TaskListSegment(list.items + TaskItem("<p></p>", checked = false)))
                }) { Text("+ Item") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove task list")
                }
            }
        }
    }
}

@Composable
private fun ImageBlock(
    image: ImageSegment,
    attachments: List<AttachmentEntity>,
    fileFor: (AttachmentEntity) -> java.io.File,
    onChange: (DocSegment) -> Unit,
    onRemove: () -> Unit,
) {
    val attachment = if (image.src.startsWith(NoteDocument.ATTACHMENT_SRC_PREFIX)) {
        attachments.firstOrNull {
            it.id == image.src.removePrefix(NoteDocument.ATTACHMENT_SRC_PREFIX)
        }
    } else {
        null
    }
    // Phase 1.5 fix 3: tap the image to select it; a drag handle then
    // sets the persisted display width (ImageSegment.widthPercent).
    var selected by remember { mutableStateOf(false) }
    var areaWidthPx by remember { mutableIntStateOf(0) }
    // The drag coroutine below is keyed by src only, so it must read the
    // LATEST segment through rememberUpdatedState — capturing `image`
    // directly would freeze the width at drag start and every delta
    // would recompute from the original width (Codex review, PR #6).
    val currentImage by rememberUpdatedState(image)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(NotaSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    image.alt.ifBlank { "Image" },
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove image")
                }
            }
            val model: Any? = when {
                attachment != null -> fileFor(attachment)
                image.src.isNotBlank() -> image.src
                else -> null
            }
            if (model != null) {
                Box(
                    Modifier.fillMaxWidth()
                        .onSizeChanged { areaWidthPx = it.width }
                        .clickable { selected = !selected },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = model,
                        contentDescription = image.alt,
                        modifier = Modifier
                            .fillMaxWidth(image.widthPercent / 100f)
                            .heightIn(max = 320.dp),
                    )
                }
                if (selected) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = NotaSpacing.xs),
                    ) {
                        Text(
                            "Width ${image.widthPercent}% — drag to resize",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier.size(width = 56.dp, height = 28.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(14.dp),
                                )
                                .pointerInput(image.src) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (areaWidthPx > 0) {
                                            val base = currentImage
                                            val delta =
                                                (dragAmount.x / areaWidthPx * 100).roundToInt()
                                            val next = (base.widthPercent + delta)
                                                .coerceIn(
                                                    NoteDocument.IMAGE_MIN_WIDTH_PERCENT,
                                                    NoteDocument.IMAGE_MAX_WIDTH_PERCENT,
                                                )
                                            if (next != base.widthPercent) {
                                                onChange(base.copy(widthPercent = next))
                                            }
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = "Drag to resize image",
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentsSection(
    attachments: List<AttachmentEntity>,
    onAddImage: () -> Unit,
    onAddFile: () -> Unit,
    onInsertImage: (AttachmentEntity) -> Unit,
    onDelete: (AttachmentEntity) -> Unit,
) {
    Column(Modifier.padding(top = NotaSpacing.sm)) {
        Text("Attachments", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(NotaSpacing.xs))
        if (attachments.isEmpty()) {
            Text(
                "No attachments yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        attachments.forEach { attachment ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (attachment.mimeType.startsWith("image/")) Icons.Filled.Image else Icons.Filled.AttachFile,
                    contentDescription = null,
                )
                Spacer(Modifier.width(NotaSpacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(attachment.fileName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${attachment.mimeType} · ${attachment.sizeBytes / 1024} KB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (attachment.mimeType.startsWith("image/")) {
                    TextButton(onClick = { onInsertImage(attachment) }) { Text("Insert") }
                }
                IconButton(onClick = { onDelete(attachment) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete attachment")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
            TextButton(onClick = onAddImage) { Text("Add image") }
            TextButton(onClick = onAddFile) { Text("Add file") }
        }
    }
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun EditorToolbar(
    state: RichTextState?,
    modifier: Modifier = Modifier,
    insertMenuExpanded: Boolean,
    onInsertMenuChange: (Boolean) -> Unit,
    onLinkRequest: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertFile: () -> Unit,
    onInsertBlock: (DocSegment) -> Unit,
) {
    val enabled = state != null
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = NotaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton("B", enabled, bold = true) {
            state?.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        }
        ToolbarButton("I", enabled, italic = true) {
            state?.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        }
        ToolbarButton("U", enabled, underline = true) {
            state?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
        }
        ToolbarButton("S", enabled, strike = true) {
            state?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
        }
        ToolbarButton("code", enabled) { state?.toggleCodeSpan() }
        ToolbarButton("H1", enabled) { state?.setHeadingStyle(HeadingStyle.H1) }
        ToolbarButton("H2", enabled) { state?.setHeadingStyle(HeadingStyle.H2) }
        ToolbarButton("H3", enabled) { state?.setHeadingStyle(HeadingStyle.H3) }
        ToolbarButton("¶", enabled) { state?.setHeadingStyle(HeadingStyle.Normal) }
        IconButton(onClick = { state?.toggleUnorderedList() }, enabled = enabled) {
            Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Bullet list")
        }
        IconButton(onClick = { state?.toggleOrderedList() }, enabled = enabled) {
            Icon(Icons.Filled.FormatListNumbered, contentDescription = "Numbered list")
        }
        IconButton(onClick = onLinkRequest, enabled = enabled) {
            Icon(Icons.Filled.Link, contentDescription = "Insert link")
        }
        listOf(0xFFC0392B, 0xFF1D6FB8, 0xFF1E7D4F).forEach { argb ->
            IconButton(
                onClick = { state?.toggleSpanStyle(SpanStyle(color = Color(argb))) },
                enabled = enabled,
            ) {
                Box(
                    Modifier.size(18.dp)
                        .background(Color(argb), RoundedCornerShape(4.dp)),
                )
            }
        }
        IconButton(
            onClick = {
                state?.toggleSpanStyle(SpanStyle(background = Color(0xFFFFEB3B)))
            },
            enabled = enabled,
        ) {
            Box(
                Modifier.size(18.dp)
                    .background(Color(0xFFFFEB3B), RoundedCornerShape(4.dp)),
            )
        }
        Box {
            IconButton(onClick = { onInsertMenuChange(true) }) {
                Icon(Icons.Filled.Add, contentDescription = "Insert block")
            }
            DropdownMenu(
                expanded = insertMenuExpanded,
                onDismissRequest = { onInsertMenuChange(false) },
            ) {
                DropdownMenuItem(
                    text = { Text("Image…") },
                    leadingIcon = { Icon(Icons.Filled.Image, contentDescription = null) },
                    onClick = { onInsertMenuChange(false); onInsertImage() },
                )
                DropdownMenuItem(
                    text = { Text("File attachment…") },
                    leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                    onClick = { onInsertMenuChange(false); onInsertFile() },
                )
                DropdownMenuItem(
                    text = { Text("Table") },
                    onClick = {
                        onInsertMenuChange(false)
                        onInsertBlock(
                            TableSegment(
                                listOf(listOf("", ""), listOf("", "")),
                                headerRow = false,
                            ),
                        )
                    },
                )
                DropdownMenuItem(
                    text = { Text("Task list") },
                    onClick = {
                        onInsertMenuChange(false)
                        onInsertBlock(TaskListSegment(listOf(TaskItem("<p></p>", checked = false))))
                    },
                )
                DropdownMenuItem(
                    text = { Text("Quote") },
                    onClick = { onInsertMenuChange(false); onInsertBlock(QuoteSegment("<p></p>")) },
                )
                DropdownMenuItem(
                    text = { Text("Code block") },
                    onClick = { onInsertMenuChange(false); onInsertBlock(CodeSegment("")) },
                )
                DropdownMenuItem(
                    text = { Text("Divider") },
                    onClick = { onInsertMenuChange(false); onInsertBlock(DividerSegment) },
                )
                DropdownMenuItem(
                    text = { Text("Text") },
                    onClick = { onInsertMenuChange(false); onInsertBlock(TextSegment("")) },
                )
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    label: String,
    enabled: Boolean,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
    strike: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            label,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
            textDecoration = when {
                underline -> TextDecoration.Underline
                strike -> TextDecoration.LineThrough
                else -> TextDecoration.None
            },
        )
    }
}
