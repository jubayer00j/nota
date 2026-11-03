package app.nota.feature.editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import app.nota.data.attachments.PendingInsertKind
import app.nota.data.attachments.PendingInsertMode
import app.nota.data.attachments.PendingInsertRecord
import app.nota.data.attachments.PendingInsertStore
import app.nota.data.db.DatabaseProvider
import com.mohamedrejeb.richeditor.ui.BasicRichText
import org.koin.compose.koinInject
import app.nota.data.db.AttachmentEntity
import app.nota.data.html.CodeSegment
import app.nota.data.html.ChartSegment
import app.nota.data.html.DividerSegment
import app.nota.data.html.DocSegment
import app.nota.data.html.FileSegment
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
import app.nota.data.security.NoteLockRecord
import app.nota.data.text.PlainTextCodec
import app.nota.feature.lock.LockNoteDialog
import app.nota.feature.lock.ManageLockDialog
import app.nota.feature.lock.NoteLockGate
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

/** A copy of the list with the element at [from] moved to [to] (Phase 3.5, D4 drag reorder). */
private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }

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
    // Phase 3.6 (G2): the durable picker-insert record + the account
    // seam, injected so UI tests can substitute a temp-dir store.
    pendingInsertStore: PendingInsertStore = koinInject(),
    databaseProvider: DatabaseProvider = koinInject(),
) {
    LaunchedEffect(noteId) { viewModel.load(noteId) }
    val note by viewModel.note.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val existingTags by viewModel.tagNames.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val linkedFrom by viewModel.linkedFrom.collectAsState()
    val linkableNotes by viewModel.linkableNotes.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    // Phase 2b lock session state.
    val sessionActive by viewModel.sessionActive.collectAsState()
    val sessionHtml by viewModel.sessionHtml.collectAsState()
    val lockError by viewModel.lockError.collectAsState()
    // Phase 2.5 (F3): live lockout deadline + in-flight flag for the gate.
    val lockoutUntilMillis by viewModel.lockoutUntilMillis.collectAsState()
    val unlockInProgress by viewModel.unlockInProgress.collectAsState()
    var showLockDialog by remember { mutableStateOf(false) }
    var showManageLock by remember { mutableStateOf(false) }
    var lockDialogError by remember { mutableStateOf<String?>(null) }
    var manageFeedback by remember { mutableStateOf<String?>(null) }

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
    // Phase 3.5 (D4): the toolbar's file action inserts the picked
    // file AT THE CARET; the attachments section's Add file only
    // imports (mirrors the image flags' split of duties).
    var insertFileOnPick by remember { mutableStateOf(false) }
    // Phase 3.5 (D4): after a caret split-insert, the segment after
    // the block takes the caret at its start (applied once composed).
    var pendingCaretFocusId by remember { mutableStateOf<String?>(null) }
    val segmentFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    // Phase 3.5 (D4): block drag-reorder state — the dragged
    // segment's id and its accumulated vertical translation.
    var dragSegmentId by remember { mutableStateOf<String?>(null) }
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }
    // Phase 3.6 (G1): layout heights of the segment rows, so the
    // dragged block's slot can stay behind as the drop indicator.
    val segmentHeights = remember { mutableStateMapOf<String, Int>() }
    // Phase 3.6 (G4): true while a two-finger image pinch is live —
    // the list must not scroll under it.
    var imagePinchActive by remember { mutableStateOf(false) }
    // Phase 3.6 (G2): while a picker insert's flush is in flight the
    // editor is frozen (barrier at the end of the Scaffold body) —
    // edits landing between the snapshot and the picker launch are
    // in neither the flushed body nor the staged record, and a
    // process death in the picker would lose them.
    var pickerFlushPending by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // Phase 3.6 (G3): read mode. A note opens for READING (Noma
    // parity); editing is entered deliberately — tap on the body or
    // the pencil — and ✓/back returns here, not out of the note. The
    // mode is ephemeral UI state, never persisted. [editEntryTarget]
    // is the (segmentId, caretOffset) a read-mode tap asked the edit
    // mode to open with, applied once the editors compose.
    var editing by remember { mutableStateOf(false) }
    var editEntryTarget by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val density = LocalDensity.current
    val view = LocalView.current

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val bringIntoView = remember { BringIntoViewRequester() }
    // Phase 1.5 fix 2: focus target for "tap below the last block".
    val trailingFocusRequester = remember { FocusRequester() }
    // Phase 2.8 (E1): when a below-content tap grows a separate
    // padding segment (document ends in a list), the new segment's
    // editor only exists after recomposition — focus + caret are
    // applied by the effect below once it has composed.
    var pendingPaddingFocusId by remember { mutableStateOf<String?>(null) }

    val current = note
    // Phase 2b: a locked note initialises from the SESSION's decrypted
    // HTML only — the stored contentHtml is an envelope and must never
    // reach the editor. While gated, sourceHtml stays null and no
    // segments are built at all.
    val sourceHtml = if (current?.locked == true) sessionHtml else current?.contentHtml
    if (current != null && sourceHtml != null && initializedFor != current.id) {
        initializedFor = current.id
        title = current.title
        folderId = current.folderId
        segments = NoteDocument.ensureTrailingTextSegment(
            NoteDocument.parse(sourceHtml),
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

    /**
     * Phase 3.5 (D4): inserts [block] AT the caret of the active text
     * or quote segment, splitting the segment there — the pre-3.5
     * insert path always landed attachment blocks at the very end of
     * the note. When the active segment is itself a block (or nothing
     * is active, or its state was never created) this falls back to
     * [insertBlock]'s after-the-active-segment placement. Empty split
     * halves are dropped by the segment-type emptiness rules; the
     * surviving left half keeps the active segment's id, with its
     * live state re-seeded to exactly the left HTML; the caret lands
     * at the start of the segment after the block (via
     * [pendingCaretFocusId]).
     */
    fun insertBlockAtCaret(block: DocSegment) {
        val activeIndex = segments.indexOfFirst { it.id == activeSegmentId }
        val active = segments.getOrNull(activeIndex)
        val activeSegment = active?.segment
        val state = active?.let { textStates[it.id] }
        if (active == null || state == null ||
            (activeSegment !is TextSegment && activeSegment !is QuoteSegment)
        ) {
            insertBlock(block)
            return
        }
        val isQuote = activeSegment is QuoteSegment
        val split = splitHtmlAtCaret(state)
        fun sideKept(html: String): Boolean = if (isQuote) {
            PlainTextCodec.htmlToText(html).isNotBlank()
        } else {
            !NoteDocument.isEmptyTextSegment(TextSegment(html))
        }
        val blockUi = UiSegment(newUiId(), block)
        val rebuilt = mutableListOf<UiSegment>()
        rebuilt += segments.subList(0, activeIndex)
        if (sideKept(split.leftHtml)) {
            state.setHtml(split.leftHtml)
            rebuilt += UiSegment(
                active.id,
                if (isQuote) QuoteSegment(split.leftHtml) else TextSegment(split.leftHtml),
            )
        } else {
            textStates.remove(active.id)
            segmentFocusRequesters.remove(active.id)
        }
        rebuilt += blockUi
        var focusId: String? = null
        if (sideKept(split.rightHtml)) {
            val rightUi = UiSegment(
                newUiId(),
                if (isQuote) QuoteSegment(split.rightHtml) else TextSegment(split.rightHtml),
            )
            rebuilt += rightUi
            focusId = rightUi.id
        }
        rebuilt += segments.subList(activeIndex + 1, segments.size)
        segments = ensureTrailing(rebuilt)
        if (focusId == null) {
            // No right half (the caret sat at the end of the text).
            // The caret belongs DIRECTLY after the inserted block:
            // if the segment immediately after it is text/quote,
            // focus that; otherwise the next content is another
            // block, so create a caret segment between the two
            // blocks rather than letting typing resume at some
            // later text segment (the trailing tail always exists,
            // so a plain "next text anywhere" lookup would skip
            // past the intervening block). The fresh segment is
            // empty mid-document text — it serialises to nothing
            // until typed in. (Codex, PR #18, two rounds.)
            val blockIndex = segments.indexOfFirst { it.id == blockUi.id }
            val next = segments.getOrNull(blockIndex + 1)
            focusId = if (next?.segment is TextSegment || next?.segment is QuoteSegment) {
                next.id
            } else {
                val caretUi = UiSegment(newUiId(), TextSegment(""))
                segments = ensureTrailing(
                    segments.toMutableList().also { it.add(blockIndex + 1, caretUi) },
                )
                caretUi.id
            }
        }
        if (focusId != null) {
            activeSegmentId = focusId
            pendingCaretFocusId = focusId
        }
    }

    /** Phase 3.6 (G1): a small tick when a dragged block locks into
     *  a new slot — the landing is felt as well as seen. */
    fun hapticTick() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /**
     * Phase 3.5 (D4) drag-reorder, Phase 3.6 (G1) refactor: evaluates
     * ONE centre-crossing swap for the dragged block at its current
     * accumulated translation and returns true when a swap happened.
     * The dragged block follows the finger via a graphicsLayer
     * translation; when its centre crosses a neighbouring SEGMENT's
     * centre the two swap in the working list and the accumulated
     * translation is adjusted by the neighbour's height (+ the
     * inter-segment spacing) so the block stays under the finger.
     * Neighbours are located through the list's layout info by item
     * key — title/meta/attachments rows, whose keys are not segment
     * ids, can never be swap targets, so a block moves among segments
     * only. Called per finger move AND per auto-scroll step (G1), so
     * a block can be walked the length of a long note.
     */
    fun evaluateBlockSwap(ui: UiSegment): Boolean {
        val from = segments.indexOfFirst { it.id == ui.id }
        if (from < 0) return false
        val visible = listState.layoutInfo.visibleItemsInfo
        val info = visible.firstOrNull { it.key == ui.id } ?: run {
            DragProbe.log("eval: dragged not visible")
            return false
        }
        val centre = info.offset + dragOffsetPx + info.size / 2f
        DragProbe.log(
            "eval from=$from vp=${listState.layoutInfo.viewportSize.height} " +
                "items=${visible.joinToString(",") { "${it.key.toString().takeLast(4)}@${it.offset}+${it.size}" }} " +
                "dragOff=$dragOffsetPx centre=$centre",
        )
        val spacingPx = with(density) { NotaSpacing.sm.toPx() }
        val prev = segments.getOrNull(from - 1)?.let { neighbour ->
            visible.firstOrNull { it.key == neighbour.id }
        }
        if (prev != null && centre < prev.offset + prev.size / 2f) {
            segments = segments.moved(from, from - 1)
            dragOffsetPx += prev.size + spacingPx
            DragProbe.log("SWAP up from=$from")
            return true
        }
        val next = segments.getOrNull(from + 1)?.let { neighbour ->
            visible.firstOrNull { it.key == neighbour.id }
        }
        if (next != null && centre > next.offset + next.size / 2f) {
            // Never swap past the trailing caret segment: an empty
            // text tail is not content to pass but the Phase 1.5
            // guarantee itself — a block stopped above it is already
            // the last content. (Codex, PR #18.)
            val nextIsTailCaret = from + 1 == segments.lastIndex &&
                (segments[from + 1].segment as? TextSegment)
                    ?.let { NoteDocument.isEmptyTextSegment(it) } == true
            if (!nextIsTailCaret) {
                segments = segments.moved(from, from + 1)
                // A block swapped below a real final text segment
                // lands last; restore the caret tail below it.
                segments = ensureTrailing(segments)
                dragOffsetPx -= next.size + spacingPx
                DragProbe.log("SWAP down from=$from")
                return true
            }
        }
        return false
    }

    fun onBlockDrag(ui: UiSegment, dragAmountY: Float) {
        dragOffsetPx += dragAmountY
        DragProbe.log("onBlockDrag dy=$dragAmountY total=$dragOffsetPx")
        if (evaluateBlockSwap(ui)) hapticTick()
    }

    // --- Phase 3.6 (G3): read ⇄ edit mode transitions ----------------

    /** Enters edit mode; when [segmentId] names a text/quote segment,
     *  the caret is placed at [caretOffset] inside it once the
     *  editors compose (the read-mode tap's position, mapped through
     *  the read renderer's own text layout). */
    fun enterEdit(segmentId: String?, caretOffset: Int?) {
        editing = true
        if (segmentId != null) {
            editEntryTarget = segmentId to (caretOffset ?: 0)
        }
    }

    /** ✓ / back in edit mode: flush and return to reading the SAME
     *  note — never out to the list. */
    fun exitToRead() {
        persistNow()
        editing = false
    }

    /** Where the caret should go when a read-mode BLOCK is tapped:
     *  the start of the next text/quote segment, else the end of the
     *  previous one — the block itself holds no caret. */
    fun entryTargetForBlock(blockId: String): Pair<String, Int>? {
        val idx = segments.indexOfFirst { it.id == blockId }
        if (idx < 0) return null
        for (i in idx + 1 until segments.size) {
            val s = segments[i].segment
            if (s is TextSegment || s is QuoteSegment) return segments[i].id to 0
        }
        for (i in idx - 1 downTo 0) {
            val s = segments[i].segment
            if (s is TextSegment || s is QuoteSegment) {
                val length = textStates[segments[i].id]
                    ?.annotatedString?.text?.length ?: 0
                return segments[i].id to length
            }
        }
        return null
    }

    /** Retires a still-staged picker record for this note (Phase 3.6,
     *  G2) — see [leaveNote]. */
    fun retirePendingInsert() {
        pendingInsertStore.peek()
            ?.takeIf { it.noteId == noteId }
            ?.let { pendingInsertStore.clearIf(it.id) }
    }

    /** Leaves the note. If a picker insert is still staged for this
     *  note (its picker never returned a result — the durable record
     *  outlived its usefulness the moment the user walks away), retire
     *  it so a later launch doesn't route back into a finished visit. */
    fun leaveNote() {
        retirePendingInsert()
        persistNow(onBack)
    }

    /**
     * Phase 2.8 (E1): after a below-content tap grows the document,
     * settle the list so the tapped region — the caret sits at its top
     * edge, in the last segment — rests mid-viewport instead of below
     * the fold or behind the keyboard.
     */
    fun scrollTailIntoView() {
        // Item indices: title, meta, the segments, then below-blocks.
        val tailIndex = segments.size + 2
        val viewportPx = listState.layoutInfo.viewportSize.height
        if (viewportPx <= 0) return
        scope.launch {
            runCatching {
                listState.animateScrollToItem(
                    tailIndex,
                    (viewportPx * 0.45f).roundToInt(),
                )
            }
        }
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

    // Phase 2.8 (E1): focus the padding segment a below-content tap
    // just appended, with the caret in its last paragraph.
    LaunchedEffect(pendingPaddingFocusId) {
        val id = pendingPaddingFocusId ?: return@LaunchedEffect
        val ui = segments.firstOrNull { it.id == id }
        if (ui != null) {
            val st = richStateFor(ui)
            st.selection = TextRange(st.annotatedString.text.length)
            runCatching { trailingFocusRequester.requestFocus() }
        }
        pendingPaddingFocusId = null
    }

    // Phase 3.5 (D4): after a caret split-insert, the caret goes to
    // the start of the segment following the inserted block.
    LaunchedEffect(pendingCaretFocusId) {
        val id = pendingCaretFocusId ?: return@LaunchedEffect
        val ui = segments.firstOrNull { it.id == id }
        if (ui != null) {
            val st = richStateFor(ui)
            st.selection = TextRange(0)
            runCatching { segmentFocusRequesters[id]?.requestFocus() }
        }
        pendingCaretFocusId = null
    }

    // Phase 3.6 (G3): a read-mode tap asked for edit mode at a
    // specific caret — apply it once the editors have composed.
    LaunchedEffect(editing, editEntryTarget) {
        if (!editing) return@LaunchedEffect
        val (segmentId, offset) = editEntryTarget ?: return@LaunchedEffect
        val ui = segments.firstOrNull { it.id == segmentId }
        if (ui != null) {
            val st = richStateFor(ui)
            st.selection = TextRange(
                offset.coerceIn(0, st.annotatedString.text.length),
            )
            runCatching { segmentFocusRequesters[segmentId]?.requestFocus() }
        }
        editEntryTarget = null
    }

    // Phase 3.6 (G1): auto-scroll while a block drag rides the
    // viewport's edge — the finger can hold still at the edge while
    // the document walks beneath it, swap by swap. This removes the
    // Phase 3.5 limitation (a drag stayed within the composed
    // window). The step grows with how deep into the edge zone the
    // dragged block's visual edge sits.
    LaunchedEffect(dragSegmentId) {
        val id = dragSegmentId ?: return@LaunchedEffect
        val zonePx = with(density) { 72.dp.toPx() }
        val maxStepPx = with(density) { 14.dp.toPx() }
        while (true) {
            delay(16)
            if (dragSegmentId != id) break
            val ui = segments.firstOrNull { it.id == id } ?: break
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == id } ?: continue
            val top = item.offset + dragOffsetPx
            val bottom = top + item.size
            val step = when {
                top < info.viewportStartOffset + zonePx ->
                    -maxStepPx * ((info.viewportStartOffset + zonePx - top) / zonePx)
                        .coerceIn(0.25f, 1f)
                bottom > info.viewportEndOffset - zonePx ->
                    maxStepPx * ((bottom - (info.viewportEndOffset - zonePx)) / zonePx)
                        .coerceIn(0.25f, 1f)
                else -> 0f
            }
            if (step != 0f) {
                val consumed = listState.scrollBy(step)
                if (consumed != 0f) {
                    // The list moved under the stationary finger:
                    // absorb the scroll into the drag translation so
                    // the block stays glued to the finger (and inside
                    // the edge zone) while the slots walk beneath it.
                    // Without this the block's visual edge retreats
                    // out of the zone and the scroll stalls after one
                    // step (Codex, PR #19).
                    dragOffsetPx += consumed
                    if (evaluateBlockSwap(ui)) hapticTick()
                }
            }
        }
    }

    // Phase 3.6 (G3): back in edit mode returns to reading the same
    // note; back in read mode leaves to the list, as before.
    BackHandler(enabled = editing) { exitToRead() }
    BackHandler(enabled = !editing) { leaveNote() }

    // --- Phase 3.6 (G2): picker inserts that survive leaving --------
    //
    // Pre-3.6 the pickers' intent lived in `remember` flags inside
    // this composition. Both the app-lock swap (Lock-after =
    // Immediately relocks when the picker backgrounds the app; the
    // whole nav shell is destroyed, note route and all) and process
    // death in the picker destroyed those flags AND the route back:
    // the file landed in the note (on a later manual reopen, when the
    // registry finally delivered the held result — import-only, the
    // insert intent gone), but the user was left at the notes list.
    // Now the intent is staged durably BEFORE launch and consumed
    // from the record, not from composition memory.

    /** Stages the durable record for a picker about to launch, then
     *  runs [onStaged] — the picker launch itself. The working
     *  document is flushed first AND the staging waits for the
     *  flush: persist is asynchronous, so staging on the caller's
     *  timeline would let a process death in the picker resurrect
     *  the pre-flush body, losing the very edits the staged index,
     *  anchor and caret describe (Codex P2, PR #19). Every persist
     *  path invokes its completion callback (including the
     *  no-change and no-session-key branches), so [onStaged] always
     *  runs exactly once. */
    fun stagePendingInsert(
        kind: PendingInsertKind,
        mode: PendingInsertMode,
        onStaged: () -> Unit,
    ) {
        val activeIndex = segments.indexOfFirst { it.id == activeSegmentId }
        val activeUi = segments.getOrNull(activeIndex)
        val activeSegment = activeUi?.segment
        val textLike = activeSegment is TextSegment || activeSegment is QuoteSegment
        val anchor: String
        val caret: Int
        val caretEnd: Int
        if (mode == PendingInsertMode.INSERT_AT_CARET && activeUi != null && textLike) {
            val state = textStates[activeUi.id]
            // The anchor identifies the target segment after a
            // rebuild; only its DIGEST is staged (the record is a
            // plaintext file — a locked note's words must not sit
            // in it. Codex P1, PR #19).
            anchor = (state?.annotatedString?.text ?: "").take(INSERT_ANCHOR_CHARS)
            caret = state?.selection?.start ?: 0
            caretEnd = state?.selection?.end ?: caret
        } else {
            anchor = ""
            caret = 0
            caretEnd = 0
        }
        val record = PendingInsertRecord(
            accountId = databaseProvider.accountId.value,
            noteId = noteId,
            kind = kind,
            mode = mode,
            segmentIndex = if (mode == PendingInsertMode.INSERT_AT_CARET) {
                activeIndex
            } else {
                -1
            },
            segmentAnchorHash = PendingInsertRecord.anchorHash(anchor),
            segmentAnchorLength = anchor.length,
            caretOffset = caret,
            caretEndOffset = caretEnd,
        )
        // Stage only once the flush has landed (see the doc above):
        // the record and the picker both belong after the write.
        // Freeze input first: from here to the picker launch the
        // snapshot below is the document of record (see
        // pickerFlushPending).
        pickerFlushPending = true
        focusManager.clearFocus()
        persistNow {
            pendingInsertStore.stage(record)
            pickerFlushPending = false
            onStaged()
        }
    }

    /** Inserts [block] at the caret target a staged record saved:
     *  resolve the target segment against the CURRENT list, put the
     *  caret back where the user left it, then run the ordinary
     *  Phase 3.5 caret split-insert. Unresolvable targets degrade to
     *  the ordinary caret insert rather than losing the block. */
    fun insertBlockAtRecordTarget(record: PendingInsertRecord, block: DocSegment) {
        val candidates = segments.map { ui ->
            val textLike = ui.segment is TextSegment || ui.segment is QuoteSegment
            val text = textStates[ui.id]?.annotatedString?.text
                ?: when (val s = ui.segment) {
                    is TextSegment -> PlainTextCodec.htmlToText(s.html)
                    is QuoteSegment -> PlainTextCodec.htmlToText(s.innerHtml)
                    else -> ""
                }
            textLike to text
        }
        val targetIndex = resolveInsertTargetIndex(record, candidates)
        val target = targetIndex?.let { segments.getOrNull(it) }
        if (target == null) {
            insertBlockAtCaret(block)
            return
        }
        activeSegmentId = target.id
        // Restore the SELECTION, not just the caret: Phase 3.5's
        // caret insert deletes a selected range (insert-over-
        // selection), and the round trip must not silently degrade
        // that into an insert-at-start (Codex, PR #19).
        val length = textStates[target.id]?.annotatedString?.text?.length ?: 0
        val start = record.caretOffset.coerceIn(0, length)
        val end = record.selectionEnd.coerceIn(0, length)
        richStateFor(target).selection =
            TextRange(minOf(start, end), maxOf(start, end))
        insertBlockAtCaret(block)
    }

    /** The single funnel every picker result passes through. The
     *  staged record (never a composition flag) decides whether the
     *  pick becomes a block at the saved caret; a cancel simply
     *  retires the record — the user is already back in the note. */
    fun handlePicked(kind: PendingInsertKind, uri: Uri?) {
        val record = pendingInsertStore.peek()
            ?.takeIf { it.noteId == noteId && it.kind == kind }
        if (record != null) pendingInsertStore.clearIf(record.id)
        if (uri == null) return
        val insert = record?.mode == PendingInsertMode.INSERT_AT_CARET ||
            (record == null && when (kind) {
                PendingInsertKind.IMAGE -> insertImageOnPick
                PendingInsertKind.FILE -> insertFileOnPick
            })
        when (kind) {
            PendingInsertKind.IMAGE -> viewModel.importImage(uri) { attachment ->
                insertImageOnPick = false
                if (attachment != null && insert) {
                    val block = ImageSegment(
                        NoteDocument.ATTACHMENT_SRC_PREFIX + attachment.id,
                        attachment.fileName,
                    )
                    // Phase 3.5 (D4): at the caret, not the note's end.
                    if (record != null) {
                        insertBlockAtRecordTarget(record, block)
                    } else {
                        insertBlockAtCaret(block)
                    }
                }
            }
            PendingInsertKind.FILE -> viewModel.importFile(uri) { attachment ->
                insertFileOnPick = false
                if (attachment != null && insert) {
                    val block = FileSegment(
                        attachmentId = attachment.id,
                        fileName = attachment.fileName,
                        mimeType = attachment.mimeType,
                    )
                    if (record != null) {
                        insertBlockAtRecordTarget(record, block)
                    } else {
                        insertBlockAtCaret(block)
                    }
                }
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? -> handlePicked(PendingInsertKind.IMAGE, uri) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> handlePicked(PendingInsertKind.FILE, uri) }

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
                    // Phase 3.6 (G3): in edit mode the back arrow
                    // returns to reading this note; in read mode it
                    // leaves to the list.
                    IconButton(
                        onClick = { if (editing) exitToRead() else leaveNote() },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Phase 3.6 (G3): while the per-note lock gate is
                    // up there is no note to read or edit yet — the
                    // mode actions appear only once the gate passes.
                    val gated = current?.locked == true && !sessionActive
                    if (!gated) {
                        if (editing) {
                            IconButton(onClick = { exitToRead() }) {
                                Icon(Icons.Filled.Check, contentDescription = "Done editing")
                            }
                        } else {
                            IconButton(onClick = { enterEdit(null, null) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit note")
                            }
                        }
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
                        if (current?.locked != true) {
                            DropdownMenuItem(
                                text = { Text("Save as template") },
                                onClick = {
                                    templateName = title.ifBlank { "Untitled template" }
                                    showTemplateDialog = true
                                    menuExpanded = false
                                },
                            )
                        }
                        // Phase 2b per-note lock actions.
                        if (current?.locked == true) {
                            DropdownMenuItem(
                                text = { Text("Manage lock…") },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                onClick = { showManageLock = true; menuExpanded = false },
                            )
                        } else if (current != null) {
                            DropdownMenuItem(
                                text = { Text("Lock note…") },
                                leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                                onClick = {
                                    lockDialogError = null
                                    showLockDialog = true
                                    menuExpanded = false
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Archive") },
                            leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                retirePendingInsert()
                                persistNow { viewModel.archive(onBack) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to trash") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                retirePendingInsert()
                                persistNow { viewModel.delete(onBack) }
                            },
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
        if (current.locked && !sessionActive) {
            // Phase 2b gate: no editor content exists in this branch —
            // only the title, the lock affordance and the credential
            // prompt for the methods this note actually has.
            val record = NoteLockRecord.parse(current.lockRecordJson)
            if (record != null) {
                NoteLockGate(
                    noteTitle = current.title,
                    record = record,
                    error = lockError,
                    onBack = onBack,
                    onSubmitCredential = { method, secret ->
                        viewModel.unlockWithCredential(method, secret)
                    },
                    biometricCipherProvider = { viewModel.biometricUnlockCipher() },
                    onBiometricUnlock = { authorised -> viewModel.unlockWithBiometric(authorised) },
                    modifier = Modifier.padding(padding),
                    lockoutUntilMillis = lockoutUntilMillis,
                    unlockInProgress = unlockInProgress,
                )
            } else {
                Column(Modifier.fillMaxSize().padding(padding).padding(NotaSpacing.lg)) {
                    Text(
                        "This note is locked, but its lock record is unreadable.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = listState,
                // Phase 3.5 (D4): while a block drag is live the list
                // itself must not start scrolling under the gesture.
                // Phase 3.6 (G4): the same while an image pinch is live.
                // (Auto-scroll during a drag is programmatic scrollBy,
                // which userScrollEnabled does not gate.)
                userScrollEnabled = dragSegmentId == null && !imagePinchActive,
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(NotaSpacing.md),
                verticalArrangement = Arrangement.spacedBy(NotaSpacing.sm),
            ) {
                item(key = "title") {
                    Column {
                        if (editing) {
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
                        } else {
                            // Phase 3.6 (G3): the read-mode title is
                            // text, not a field — tapping it is one of
                            // the doors into edit mode.
                            Text(
                                title.ifBlank { "Untitled note" },
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { enterEdit(null, null) }
                                    .padding(vertical = NotaSpacing.xs),
                            )
                        }
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
                    if (editing) {
                        NoteMetaRow(
                            folders = folders,
                            folderId = folderId,
                            onFolderSelected = { folderId = it },
                            tags = parseTagsCsv(tagsCsv),
                            onTagsChanged = { tagsCsv = it.joinToString(", ") },
                        )
                    } else {
                        // Phase 3.6 (G3): read mode shows the folder /
                        // tags as plain context, no editors. The item
                        // stays present so segment list indices (the
                        // outline's scroll targets) are mode-stable.
                        val folderName = folders.firstOrNull { it.id == folderId }?.name
                        val tagList = parseTagsCsv(tagsCsv)
                        if (folderName != null || tagList.isNotEmpty()) {
                            Text(
                                listOfNotNull(
                                    folderName,
                                    tagList.takeIf { it.isNotEmpty() }
                                        ?.joinToString(" · ") { "#$it" },
                                ).joinToString("  "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                itemsIndexed(segments, key = { _, ui -> ui.id }) { index, ui ->
                    if (!editing) {
                        // Phase 3.6 (G3): the read rendering of the
                        // same segment — static rich text / blocks,
                        // toggleable task checkboxes, tappable links;
                        // a tap anywhere else is a door into edit mode.
                        SegmentReadView(
                            ui = ui,
                            stateFor = { richStateFor(ui) },
                            onTapText = { caret ->
                                enterEdit(ui.id, caret)
                            },
                            onTapBlock = {
                                val target = entryTargetForBlock(ui.id)
                                enterEdit(target?.first, target?.second)
                            },
                            onChange = { updateBlock(ui.id, it) },
                            attachments = attachments,
                            fileFor = { viewModel.attachmentFile(it) },
                        )
                        return@itemsIndexed
                    }
                    // Phase 3.6 (G1): EVERY block type drags — and so
                    // does a quote — by long-press on the block itself
                    // (see BlockDragGesture.kt); only plain text
                    // segments are exempt, because their long-press is
                    // text selection. The 3.5 rail remains as the
                    // visual affordance. While dragged, the block
                    // floats (translation + elevation + alpha) and its
                    // SLOT stays behind as the drop indicator: the
                    // highlighted gap is exactly where it will land,
                    // because swaps are live.
                    val isDraggable = ui.segment !is TextSegment
                    val isBlock = ui.segment !is TextSegment && ui.segment !is QuoteSegment
                    val isDragged = dragSegmentId == ui.id
                    Box(
                        Modifier.onSizeChanged { segmentHeights[ui.id] = it.height },
                    ) {
                        if (isDragged) {
                            val slotHeight = segmentHeights[ui.id]
                            Box(
                                Modifier.fillMaxWidth()
                                    .then(
                                        if (slotHeight != null) {
                                            Modifier.height(
                                                with(density) { slotHeight.toDp() },
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer
                                            .copy(alpha = 0.55f),
                                        RoundedCornerShape(8.dp),
                                    )
                                    .border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(8.dp),
                                    ),
                            )
                        }
                        Box(
                            (if (isDragged) {
                                Modifier.zIndex(1f).graphicsLayer {
                                    translationY = dragOffsetPx
                                    alpha = 0.92f
                                    shadowElevation = with(density) { 8.dp.toPx() }
                                }
                            } else {
                                Modifier
                            }).then(
                                if (isDraggable) {
                                    Modifier.longPressBlockDrag(
                                        key = ui.id,
                                        onDragStart = {
                                            DragProbe.log("onDragStart")
                                            dragSegmentId = ui.id
                                            dragOffsetPx = 0f
                                        },
                                        onDrag = { dy -> onBlockDrag(ui, dy) },
                                        onDragEnd = {
                                            DragProbe.log("onDragEnd")
                                            dragSegmentId = null
                                            dragOffsetPx = 0f
                                            persistNow()
                                        },
                                        onDragCancel = {
                                            DragProbe.log("onDragCancel")
                                            dragSegmentId = null
                                            dragOffsetPx = 0f
                                        },
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        ) {
                            SegmentEditor(
                                ui = ui,
                                isActive = ui.id == activeSegmentId,
                                isTrailingCaret = index == segments.lastIndex &&
                                    ui.segment is TextSegment,
                                trailingFocusRequester = trailingFocusRequester,
                                focusRequester = segmentFocusRequesters
                                    .getOrPut(ui.id) { FocusRequester() },
                                stateFor = { richStateFor(ui) },
                                onFocus = { activeSegmentId = ui.id },
                                onChange = { updateBlock(ui.id, it) },
                                onRemove = { removeBlock(ui.id) },
                                attachments = attachments,
                                fileFor = { viewModel.attachmentFile(it) },
                                bringIntoView = bringIntoView,
                                onPinchingChange = { imagePinchActive = it },
                                // The rail is the visual affordance
                                // now; the drag gesture lives on the
                                // whole block (container above).
                                dragHandleModifier = if (isBlock) Modifier else null,
                            )
                        }
                    }
                }
                if (segments.lastOrNull()?.segment is TextSegment) {
                    // Phase 1.5 fix 2 + Phase 2.8 (E1): the empty area
                    // below the last block is the tap-anywhere surface.
                    // It fills the rest of the viewport, and a tap
                    // places the caret at the tapped visual row: the
                    // document grows with empty paragraphs up to that
                    // row (see TapBelowContent.kt). A tap in the first
                    // row band keeps the Phase 1.5 behaviour — caret at
                    // the very end of the document.
                    item(key = "below-blocks") {
                        if (!editing) {
                            // Phase 3.6 (G3): in read mode the empty
                            // area below the content is a door into
                            // edit mode, caret at the document's end.
                            Box(
                                Modifier.fillMaxWidth().height(96.dp)
                                    .pointerInput(segments) {
                                        detectTapGestures {
                                            val lastText = segments.lastOrNull {
                                                it.segment is TextSegment ||
                                                    it.segment is QuoteSegment
                                            }
                                            if (lastText != null) {
                                                val length = textStates[lastText.id]
                                                    ?.annotatedString?.text?.length ?: 0
                                                enterEdit(lastText.id, length)
                                            } else {
                                                enterEdit(null, null)
                                            }
                                        }
                                    },
                            )
                            return@item
                        }
                        val density = LocalDensity.current
                        val layoutInfo = listState.layoutInfo
                        val tailInfo = layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == "below-blocks" }
                        val tailHeight = if (
                            tailInfo != null && layoutInfo.viewportSize.height > 0
                        ) {
                            val remainingPx =
                                layoutInfo.viewportSize.height - tailInfo.offset
                            with(density) {
                                val minPx = 96.dp.roundToPx()
                                if (layoutInfo.viewportSize.height <= minPx) {
                                    96.dp
                                } else {
                                    remainingPx.coerceIn(
                                        minPx,
                                        layoutInfo.viewportSize.height,
                                    ).toDp()
                                }
                            }
                        } else {
                            96.dp
                        }
                        Box(
                            Modifier.fillMaxWidth().height(tailHeight)
                                .pointerInput(segments) {
                                    detectTapGestures { offset ->
                                        val ui = segments.lastOrNull()
                                            ?: return@detectTapGestures
                                        val state = richStateFor(ui)
                                        activeSegmentId = ui.id
                                        runCatching {
                                            trailingFocusRequester.requestFocus()
                                        }
                                        val rows = tapRowsBelowContent(
                                            tapYInTailPx = offset.y,
                                            gapAboveTailPx = with(density) {
                                                NotaSpacing.sm.toPx()
                                            },
                                            lineHeightPx = with(density) {
                                                NotaEditorBody.lineHeight.toPx()
                                            },
                                        )
                                        if (rows <= 0) {
                                            state.placeCaretBelowContent(0)
                                            return@detectTapGestures
                                        }
                                        if (state.placeCaretBelowContent(rows)) {
                                            scrollTailIntoView()
                                        } else {
                                            // The document ends inside a
                                            // list: the padding must be
                                            // its own text segment below
                                            // the list, not list items.
                                            val padding = UiSegment(
                                                newUiId(),
                                                TextSegment(paddingSegmentHtml(rows)),
                                            )
                                            segments = segments + padding
                                            activeSegmentId = padding.id
                                            pendingPaddingFocusId = padding.id
                                            scrollTailIntoView()
                                        }
                                    }
                                },
                        )
                    }
                }
                item(key = "attachments") {
                    AttachmentsSection(
                        attachments = attachments,
                        readOnly = !editing,
                        onAddImage = {
                            // Phase 3.6 (G2): stage durably BEFORE the
                            // picker opens — the return route and the
                            // import intent must survive the lock swap
                            // and process death alike.
                            stagePendingInsert(
                                PendingInsertKind.IMAGE,
                                PendingInsertMode.IMPORT_ONLY,
                            ) {
                                imagePicker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            }
                        },
                        onAddFile = {
                            stagePendingInsert(
                                PendingInsertKind.FILE,
                                PendingInsertMode.IMPORT_ONLY,
                            ) {
                                filePicker.launch(arrayOf("*/*"))
                            }
                        },
                        onInsertImage = { attachment ->
                            // Phase 3.5 (D4): at the caret, not appended.
                            insertBlockAtCaret(
                                ImageSegment(
                                    NoteDocument.ATTACHMENT_SRC_PREFIX + attachment.id,
                                    attachment.fileName,
                                ),
                            )
                        },
                        onInsertFile = { attachment ->
                            insertBlockAtCaret(
                                FileSegment(
                                    attachmentId = attachment.id,
                                    fileName = attachment.fileName,
                                    mimeType = attachment.mimeType,
                                ),
                            )
                        },
                        onDelete = { attachment ->
                            // Drop any embedded block for this
                            // attachment from the working segments too
                            // (Codex F7; Phase 3.5 extends it to file
                            // blocks) so autosave cannot resurrect it;
                            // the VM strips it from the stored document.
                            val targetSrc =
                                NoteDocument.ATTACHMENT_SRC_PREFIX + attachment.id
                            segments = ensureTrailing(
                                segments.filterNot {
                                    (it.segment as? ImageSegment)?.src == targetSrc ||
                                        (it.segment as? FileSegment)?.attachmentId == attachment.id
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
                                TextButton(
                                    onClick = {
                                        retirePendingInsert()
                                        persistNow { onOpenNote(source.id) }
                                    },
                                ) {
                                    Text(source.title.ifBlank { "Untitled note" })
                                }
                            }
                        }
                    }
                }
            }
            if (editing && linkSuggestions.isNotEmpty() && linkTrigger != null) {
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
            if (editing) {
                EditorToolbar(
                    state = activeState,
                    modifier = Modifier.alpha(chromeAlpha),
                    insertMenuExpanded = insertMenuExpanded,
                    onInsertMenuChange = { insertMenuExpanded = it },
                    onLinkRequest = { showLinkDialog = true },
                    onInsertImage = {
                        insertImageOnPick = true
                        // Phase 3.6 (G2): the durable record is staged
                        // before the picker opens (see handlePicked).
                        stagePendingInsert(
                            PendingInsertKind.IMAGE,
                            PendingInsertMode.INSERT_AT_CARET,
                        ) {
                            imagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        }
                    },
                    onInsertFile = {
                        insertFileOnPick = true
                        stagePendingInsert(
                            PendingInsertKind.FILE,
                            PendingInsertMode.INSERT_AT_CARET,
                        ) {
                            filePicker.launch(arrayOf("*/*"))
                        }
                    },
                    onInsertBlock = { insertBlock(it) },
                )
            }
        }
        if (pickerFlushPending) {
            // Input freeze while a picker insert's flush is in
            // flight: swallow every gesture until the callback
            // stages the record and launches the picker (the
            // keyboard is already dismissed — focus was cleared
            // when the flush began).
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    }
                },
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

    if (showLockDialog) {
        LockNoteDialog(
            error = lockDialogError,
            onDismiss = { showLockDialog = false },
            onLock = { method, canonical, biometric, cipher ->
                // Flush pending edits through the normal persist first,
                // so the seal covers the latest stored body; then lock
                // and leave — reopening the note now shows the gate.
                persistNow {
                    viewModel.lockCurrentNote(method, canonical, biometric, cipher) { error ->
                        if (error == null) {
                            showLockDialog = false
                            onBack()
                        } else {
                            lockDialogError = error
                        }
                    }
                }
            },
        )
    }

    if (showManageLock) {
        val manageRecord = current?.let { NoteLockRecord.parse(it.lockRecordJson) }
        if (manageRecord != null) {
            ManageLockDialog(
                record = manageRecord,
                feedback = manageFeedback,
                onDismiss = { showManageLock = false },
                onSetSlot = { method, canonical ->
                    viewModel.setLockSlot(method, canonical) { error ->
                        manageFeedback = error
                    }
                },
                onRemoveSlot = { method ->
                    viewModel.removeLockSlot(method) { removed ->
                        manageFeedback = if (removed) {
                            null
                        } else {
                            "That credential couldn't be removed."
                        }
                    }
                },
                onSetBiometric = { enabled, cipher ->
                    viewModel.setLockBiometric(enabled, cipher) { ok ->
                        manageFeedback = if (ok) {
                            null
                        } else {
                            "Fingerprint unlock couldn't be updated on this device."
                        }
                    }
                },
                onRemoveLock = {
                    showManageLock = false
                    viewModel.removeCurrentLock { onBack() }
                },
            )
        }
    }
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun SegmentEditor(
    ui: UiSegment,
    isActive: Boolean,
    isTrailingCaret: Boolean = false,
    trailingFocusRequester: FocusRequester? = null,
    // Phase 3.5 (D4): per-segment focus requester (caret placement
    // after a split-insert) and the block drag handle's gesture
    // modifier (null for text/quote segments, which never drag).
    focusRequester: FocusRequester? = null,
    dragHandleModifier: Modifier? = null,
    stateFor: () -> RichTextState,
    onFocus: () -> Unit,
    onChange: (DocSegment) -> Unit,
    onRemove: () -> Unit,
    attachments: List<AttachmentEntity>,
    fileFor: (AttachmentEntity) -> java.io.File,
    bringIntoView: BringIntoViewRequester,
    // Phase 3.6 (G4): lets the screen suspend list scrolling while an
    // image pinch is live.
    onPinchingChange: (Boolean) -> Unit = {},
) {
    val focusModifier = Modifier.onFocusChanged { if (it.isFocused) onFocus() }
    val activeModifier = if (isActive) Modifier.bringIntoViewRequester(bringIntoView) else Modifier
    // Phase 2a rider 1 (dark/OLED editor ink): BasicRichTextEditor is the
    // FOUNDATION variant — unlike the Material3 RichTextEditor used in the
    // spike, it applies no LocalContentColor/theme colour, so a textStyle
    // with Color.Unspecified falls back to the platform default BLACK for
    // every unstyled span. On paper that looked right; on the dark/OLED
    // schemes it rendered near-black ink on a black surface. Pin the base
    // colour to onSurface: unstyled body/headings/lists/quotes inherit it
    // (heading styles derive from this base style), while spans with an
    // explicit colour from the document HTML still win over the base.
    val editorTextStyle = NotaEditorBody.copy(color = MaterialTheme.colorScheme.onSurface)
    when (val segment = ui.segment) {
        is TextSegment -> {
            val state = stateFor()
            var editorModifier = Modifier.fillMaxWidth().then(focusModifier).then(activeModifier)
            if (focusRequester != null) {
                editorModifier = editorModifier.focusRequester(focusRequester)
            }
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
                textStyle = editorTextStyle,
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
                    textStyle = editorTextStyle,
                    modifier = Modifier.fillMaxWidth().then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        },
                    ),
                )
            }
        }
        else -> {
            // Phase 3.5 (D4): every block gets a drag rail — an
            // explicit handle that long-press-drags the block among
            // the note's segments. It sits OUTSIDE the block card, so
            // it never fights text selection (blocks hold none, apart
            // from the code field, whose own gestures stay inside the
            // card) or the image width handle inside the image card.
            Row(Modifier.fillMaxWidth()) {
                if (dragHandleModifier != null) {
                    Box(
                        dragHandleModifier.width(24.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "Drag to move block",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = NotaSpacing.sm).size(20.dp),
                        )
                    }
                }
                Box(Modifier.weight(1f)) {
                    when (segment) {
                        is TableSegment -> TableBlock(ui.id, segment, onChange, onRemove)
                        is TaskListSegment -> TaskListBlock(ui.id, segment, onChange, onRemove)
                        is ImageSegment -> ImageBlock(
                            segment, attachments, fileFor, onChange, onRemove, onPinchingChange,
                        )
                        is FileSegment -> FileBlock(segment, attachments, onRemove)
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
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Phase 3.6 (G3): the READ rendering of one segment. Text and quotes
 * render through the library's read-only [BasicRichText] over the
 * SAME [RichTextState] the editor uses, so flipping modes is
 * seamless and warm; its link taps stay live (the renderer consumes
 * link taps itself — they never reach the tap-to-edit handler
 * below). Task checkboxes stay toggleable (a toggle is a state
 * change, not an edit session — Noma parity). Everything else is a
 * static rendering.
 *
 * Taps: a text tap maps through the renderer's own [TextLayoutResult]
 * to the exact caret offset edit mode should open with; a block tap
 * asks the caller for the nearest caret ([onTapBlock]).
 */
@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun SegmentReadView(
    ui: UiSegment,
    stateFor: () -> RichTextState,
    onTapText: (caretOffset: Int) -> Unit,
    onTapBlock: () -> Unit,
    onChange: (DocSegment) -> Unit,
    attachments: List<AttachmentEntity>,
    fileFor: (AttachmentEntity) -> java.io.File,
) {
    val readTextStyle = NotaEditorBody.copy(color = MaterialTheme.colorScheme.onSurface)
    when (val segment = ui.segment) {
        is TextSegment -> {
            var layout by remember(ui.id) { mutableStateOf<TextLayoutResult?>(null) }
            BasicRichText(
                state = stateFor(),
                style = readTextStyle,
                onTextLayout = { layout = it },
                modifier = Modifier.fillMaxWidth().pointerInput(ui.id) {
                    detectTapGestures { offset ->
                        onTapText(
                            layout?.getOffsetForPosition(offset)
                                ?: stateFor().annotatedString.text.length,
                        )
                    }
                },
            )
        }
        is QuoteSegment -> {
            var layout by remember(ui.id) { mutableStateOf<TextLayoutResult?>(null) }
            Row(Modifier.fillMaxWidth()) {
                Box(
                    Modifier.width(3.dp).heightIn(min = 48.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(NotaSpacing.sm))
                BasicRichText(
                    state = stateFor(),
                    style = readTextStyle,
                    onTextLayout = { layout = it },
                    modifier = Modifier.fillMaxWidth().pointerInput(ui.id) {
                        detectTapGestures { offset ->
                            onTapText(
                                layout?.getOffsetForPosition(offset)
                                    ?: stateFor().annotatedString.text.length,
                            )
                        }
                    },
                )
            }
        }
        is TaskListSegment -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().pointerInput(ui.id) {
                    detectTapGestures { onTapBlock() }
                },
            ) {
                Column(Modifier.padding(NotaSpacing.sm)) {
                    segment.items.forEachIndexed { index, item ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = item.checked,
                                onCheckedChange = { checked ->
                                    onChange(
                                        TaskListSegment(
                                            segment.items.mapIndexed { i, old ->
                                                if (i == index) {
                                                    old.copy(checked = checked)
                                                } else {
                                                    old
                                                }
                                            },
                                        ),
                                    )
                                },
                            )
                            val itemState = remember(ui.id, index, item.contentHtml) {
                                RichTextState().apply { setHtml(item.contentHtml) }
                            }
                            BasicRichText(
                                state = itemState,
                                style = readTextStyle,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        is TableSegment -> {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().pointerInput(ui.id) {
                    detectTapGestures { onTapBlock() }
                },
            ) {
                Column(Modifier.padding(NotaSpacing.sm)) {
                    segment.rows.forEachIndexed { r, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                            row.forEach { cell ->
                                Text(
                                    PlainTextCodec.htmlToText(cell),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (segment.headerRow && r == 0) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (r < segment.rows.lastIndex) {
                            Spacer(Modifier.height(NotaSpacing.xs))
                        }
                    }
                }
            }
        }
        is ImageSegment -> {
            val attachment = if (segment.src.startsWith(NoteDocument.ATTACHMENT_SRC_PREFIX)) {
                attachments.firstOrNull {
                    it.id == segment.src.removePrefix(NoteDocument.ATTACHMENT_SRC_PREFIX)
                }
            } else {
                null
            }
            val model: Any? = when {
                attachment != null -> fileFor(attachment)
                segment.src.isNotBlank() -> segment.src
                else -> null
            }
            Box(
                Modifier.fillMaxWidth().pointerInput(ui.id) {
                    detectTapGestures { onTapBlock() }
                },
                contentAlignment = Alignment.Center,
            ) {
                if (model != null) {
                    AsyncImage(
                        model = model,
                        contentDescription = segment.alt,
                        modifier = Modifier
                            .fillMaxWidth(segment.widthPercent / 100f)
                            .heightIn(max = 320.dp),
                    )
                } else {
                    Text(
                        segment.alt.ifBlank { "Image" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        is FileSegment -> {
            val attachment = attachments.firstOrNull { it.id == segment.attachmentId }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().pointerInput(ui.id) {
                    detectTapGestures { onTapBlock() }
                },
            ) {
                Row(
                    Modifier.padding(NotaSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(NotaSpacing.sm))
                    Column {
                        Text(
                            attachment?.fileName ?: segment.fileName,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val meta = listOfNotNull(
                            (attachment?.mimeType ?: segment.mimeType).ifBlank { null },
                            attachment?.let { "${it.sizeBytes / 1024} KB" },
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        is CodeSegment -> {
            Box(
                Modifier.fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .pointerInput(ui.id) { detectTapGestures { onTapBlock() } }
                    .padding(NotaSpacing.sm),
            ) {
                Text(
                    segment.text,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                )
            }
        }
        DividerSegment -> {
            Box(
                Modifier.fillMaxWidth().pointerInput(ui.id) {
                    detectTapGestures { onTapBlock() }
                }.padding(vertical = NotaSpacing.xs),
            ) {
                HorizontalDivider(Modifier.fillMaxWidth())
            }
        }
        else -> {
            // Chart / opaque segments: the same honest placeholder
            // the edit mode shows — preserved exactly as stored.
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().pointerInput(ui.id) {
                    detectTapGestures { onTapBlock() }
                },
            ) {
                Text(
                    if (segment is ChartSegment) {
                        "Chart — preserved as-is."
                    } else {
                        "Preserved block — kept exactly as stored."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(NotaSpacing.sm),
                )
            }
        }
    }
}

/**
 * Phase 3.5 (D4): an in-document file block. Name/size resolve live
 * from the attachments table when the row exists (it is the source
 * of truth); the segment's insert-time snapshot is the fallback, and
 * a missing row is called out instead of rendering a dead block.
 */
@Composable
private fun FileBlock(
    file: FileSegment,
    attachments: List<AttachmentEntity>,
    onRemove: () -> Unit,
) {
    val attachment = attachments.firstOrNull { it.id == file.attachmentId }
    BlockShell(onRemove = onRemove) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AttachFile, contentDescription = null)
            Spacer(Modifier.width(NotaSpacing.sm))
            Column {
                Text(
                    attachment?.fileName ?: file.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val meta = listOfNotNull(
                    (attachment?.mimeType ?: file.mimeType).ifBlank { null },
                    attachment?.let { "${it.sizeBytes / 1024} KB" },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (attachment == null) {
                    Text(
                        "Attachment not found on this device",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
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
    // Phase 3.6 (G4): suspends list scrolling while a pinch is live.
    onPinchingChange: (Boolean) -> Unit = {},
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
    // Phase 3.6 (G4): the live width while a pinch is in flight —
    // rendered immediately, persisted only when the gesture ends.
    var liveWidthPercent by remember(image.src) { mutableStateOf<Int?>(null) }
    val displayWidthPercent = liveWidthPercent ?: image.widthPercent
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
                            .fillMaxWidth(displayWidthPercent / 100f)
                            .heightIn(max = 320.dp)
                            // Phase 3.6 (G4): pinch to resize, like
                            // the gallery — two fingers spread or
                            // squeeze and the width follows the span
                            // ratio (ImagePinchMath). The width is
                            // committed through onChange on release,
                            // persisting via the existing width="N%"
                            // attribute. A gesture stream another
                            // handler already claimed (a block drag's
                            // long press consumes the first finger's
                            // changes) never starts a pinch.
                            .pointerInput(image.src) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    var pinching = false
                                    var startSpan = 0f
                                    var startPercent = 0
                                    var liveWidth: Int? = null
                                    try {
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val pressed =
                                                event.changes.filter { it.pressed }
                                            if (pressed.isEmpty()) break
                                            if (pressed.size >= 2) {
                                                val span = (
                                                    pressed[0].position -
                                                        pressed[1].position
                                                    ).getDistance()
                                                if (!pinching) {
                                                    if (pressed.any { it.isConsumed }) {
                                                        // Someone else owns
                                                        // this stream.
                                                        break
                                                    }
                                                    pinching = true
                                                    startSpan = span
                                                    startPercent =
                                                        currentImage.widthPercent
                                                    onPinchingChange(true)
                                                } else {
                                                    pressed.forEach { it.consume() }
                                                    val next = ImagePinchMath
                                                        .widthPercent(
                                                            startPercent,
                                                            startSpan,
                                                            span,
                                                        )
                                                    if (next != liveWidth) {
                                                        liveWidth = next
                                                        liveWidthPercent = next
                                                    }
                                                }
                                            } else if (pinching) {
                                                // Down to one finger: over.
                                                break
                                            }
                                        }
                                    } finally {
                                        if (pinching) {
                                            onPinchingChange(false)
                                            val finalWidth = liveWidth
                                            liveWidthPercent = null
                                            if (finalWidth != null &&
                                                finalWidth != currentImage.widthPercent
                                            ) {
                                                onChange(
                                                    currentImage.copy(
                                                        widthPercent = finalWidth,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                }
                            },
                    )
                }
                if (selected) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(top = NotaSpacing.xs),
                    ) {
                        Text(
                            "Width $displayWidthPercent% — pinch or drag to resize",
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
    onInsertFile: (AttachmentEntity) -> Unit,
    onDelete: (AttachmentEntity) -> Unit,
    // Phase 3.6 (G3): read mode lists the attachments but offers no
    // insert / add / delete actions — those are edits.
    readOnly: Boolean = false,
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
                if (!readOnly) {
                    if (attachment.mimeType.startsWith("image/")) {
                        TextButton(onClick = { onInsertImage(attachment) }) { Text("Insert") }
                    } else {
                        // Phase 3.5 (D4): files insert as document blocks
                        // at the caret, like images.
                        TextButton(onClick = { onInsertFile(attachment) }) { Text("Insert") }
                    }
                    IconButton(onClick = { onDelete(attachment) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete attachment")
                    }
                }
            }
        }
        if (!readOnly) {
            Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.sm)) {
                TextButton(onClick = onAddImage) { Text("Add image") }
                TextButton(onClick = onAddFile) { Text("Add file") }
            }
        }
    }
}

/** The one highlight colour the toolbar applies (Phase 1c swatch). */
private const val HIGHLIGHT_ARGB = 0xFFFFEB3B

/** Phase 3.6 (G2): how much of the target segment's plain text a
 *  staged picker record keeps as its identity anchor. */
private const val INSERT_ANCHOR_CHARS = 60
private val HIGHLIGHT_COLOR = Color(HIGHLIGHT_ARGB)

/**
 * Phase 2.8 (E2): selected treatment for the toolbar's icon buttons —
 * a primaryContainer pill (theme-derived, so it reads on light, dark
 * and OLED schemes alike); unselected keeps the stock appearance.
 */
@Composable
private fun toolbarIconColors(selected: Boolean) =
    if (selected) {
        IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        IconButtonDefaults.iconButtonColors()
    }

/** A colour swatch; the selected one wears a primary ring. */
@Composable
private fun Swatch(argb: Long, selected: Boolean) {
    Box(
        Modifier.size(26.dp).then(
            if (selected) {
                Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(6.dp),
                )
            } else {
                Modifier
            },
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(18.dp)
                .background(Color(argb), RoundedCornerShape(4.dp)),
        )
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
    // Phase 2.8 (E2): the live formatting picture at the caret/
    // selection, derived from the editor state itself — moving the
    // caret into bold text lights Bold without any click (see
    // ToolbarState.kt). Null state -> nothing selected, all disabled.
    val format = state?.toolbarFormatState()
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = NotaSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolbarButton("B", enabled, selected = format?.bold == true, bold = true) {
            state?.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
        }
        ToolbarButton("I", enabled, selected = format?.italic == true, italic = true) {
            state?.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
        }
        ToolbarButton("U", enabled, selected = format?.underline == true, underline = true) {
            state?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
        }
        ToolbarButton("S", enabled, selected = format?.strikethrough == true, strike = true) {
            state?.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
        }
        ToolbarButton("code", enabled, selected = format?.code == true) {
            state?.toggleCodeSpan()
        }
        ToolbarButton("H1", enabled, selected = format?.heading == HeadingStyle.H1) {
            state?.setHeadingStyle(HeadingStyle.H1)
        }
        ToolbarButton("H2", enabled, selected = format?.heading == HeadingStyle.H2) {
            state?.setHeadingStyle(HeadingStyle.H2)
        }
        ToolbarButton("H3", enabled, selected = format?.heading == HeadingStyle.H3) {
            state?.setHeadingStyle(HeadingStyle.H3)
        }
        ToolbarButton("¶", enabled, selected = format?.heading == HeadingStyle.Normal) {
            state?.setHeadingStyle(HeadingStyle.Normal)
        }
        IconButton(
            onClick = { state?.toggleUnorderedList() },
            enabled = enabled,
            colors = toolbarIconColors(format?.unorderedList == true),
        ) {
            Icon(Icons.AutoMirrored.Filled.FormatListBulleted, contentDescription = "Bullet list")
        }
        IconButton(
            onClick = { state?.toggleOrderedList() },
            enabled = enabled,
            colors = toolbarIconColors(format?.orderedList == true),
        ) {
            Icon(Icons.Filled.FormatListNumbered, contentDescription = "Numbered list")
        }
        IconButton(
            onClick = onLinkRequest,
            enabled = enabled,
            colors = toolbarIconColors(format?.link == true),
        ) {
            Icon(Icons.Filled.Link, contentDescription = "Insert link")
        }
        listOf(0xFFC0392B, 0xFF1D6FB8, 0xFF1E7D4F).forEach { argb ->
            IconButton(
                onClick = { state?.toggleSpanStyle(SpanStyle(color = Color(argb))) },
                enabled = enabled,
            ) {
                Swatch(argb, selected = format?.textColor == Color(argb))
            }
        }
        IconButton(
            onClick = {
                state?.toggleSpanStyle(SpanStyle(background = HIGHLIGHT_COLOR))
            },
            enabled = enabled,
        ) {
            Swatch(HIGHLIGHT_ARGB, selected = format?.highlightColor == HIGHLIGHT_COLOR)
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
    selected: Boolean = false,
    bold: Boolean = false,
    italic: Boolean = false,
    underline: Boolean = false,
    strike: Boolean = false,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        // Phase 2.8 (E2): the selected treatment — a filled
        // primaryContainer pill in the pine & paper system, visible
        // on light, dark and OLED schemes.
        colors = if (selected) {
            ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            ButtonDefaults.textButtonColors()
        },
    ) {
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
