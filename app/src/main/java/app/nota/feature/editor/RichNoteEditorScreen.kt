package app.nota.feature.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddToHomeScreen
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sell
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
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import app.nota.data.ai.AiErrorKind
import app.nota.data.ai.AiException
import app.nota.data.ai.AiService
import app.nota.data.ai.AiSummaryHtml
import app.nota.data.ai.AiSummaryOutcome
import app.nota.data.ai.AiTagOutcome
import app.nota.data.attachments.PendingInsertKind
import app.nota.data.attachments.PendingInsertMode
import app.nota.data.attachments.PendingInsertRecord
import app.nota.data.attachments.PendingInsertStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.reminders.ReminderNotificationText
import app.nota.feature.capture.NotePinShortcut
import app.nota.feature.reminders.ReminderDialog
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** One row of the editor's working document (stable id across recompositions). */
private data class UiSegment(val id: String, val segment: DocSegment)

/**
 * Phase 3.8: one live block-drag session. [fromIndex] is the
 * dragged segment's index in the working list; [grabOffsetPx] is
 * where inside the block (below its top, list-viewport px) the
 * finger claimed it — the ghost's top is always the pointer Y
 * minus this; [blockHeightPx] is the block's laid-out height at
 * the claim: the placeholder slot holds exactly this height for
 * the whole session.
 */
private data class BlockDragSession(
    val segmentId: String,
    val fromIndex: Int,
    val grabOffsetPx: Float,
    val blockHeightPx: Int,
)

private fun newUiId(): String = UUID.randomUUID().toString()

/** A copy of the list with the element at [from] moved to [to] (Phase 3.5, D4 drag reorder). */
private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    toMutableList().apply { add(to, removeAt(from)) }

/**
 * Phase 3.7 origin, Phase 3.8 role: how far a dragged block's
 * leading edge must push past a neighbour's near edge before
 * that neighbour counts as PASSED for the insertion target (see
 * updateDragTarget). A small constant — big enough to read as
 * intent, small enough that reordering even the tallest blocks
 * is a flick, not a haul. It is also the target's hysteresis:
 * once an edge has passed, the finger must retreat the full
 * overshoot to un-pass it, so a parked finger cannot flicker
 * the landing line back and forth across a boundary.
 */
private val BLOCK_SWAP_OVERSHOOT = 24.dp

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
    // Beginner AI Setup #1 (F6): where the no-key notice's
    // "Open AI settings" action goes (Settings → AI features).
    onOpenAiSettings: () -> Unit = {},
    viewModel: RichNoteEditorViewModel = koinViewModel(),
    // Phase 3.6 (G2): the durable picker-insert record + the account
    // seam, injected so UI tests can substitute a temp-dir store.
    pendingInsertStore: PendingInsertStore = koinInject(),
    databaseProvider: DatabaseProvider = koinInject(),
    // Phase 4.1: the BYOK AI orchestration (Summarize / Suggest tags).
    aiService: AiService = koinInject(),
) {
    LaunchedEffect(noteId) { viewModel.load(noteId) }
    val note by viewModel.note.collectAsState()
    val folders by viewModel.folders.collectAsState()
    val existingTags by viewModel.tagNames.collectAsState()
    val libraryTagNames by viewModel.libraryTagNames.collectAsState()
    val attachments by viewModel.attachments.collectAsState()
    val linkedFrom by viewModel.linkedFrom.collectAsState()
    val links by viewModel.links.collectAsState()
    val linkableNotes by viewModel.linkableNotes.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val reminder by viewModel.reminder.collectAsState()
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
    // Phase 3.8 (Codex finding 3, PR #21): table-cell / task-item
    // field text, hoisted ABOVE the block editors (the textStates
    // pattern). A block's editor composes in two places over a
    // drag — its list slot, then the session GHOST, then the slot
    // again — and a remember() inside the field does not survive
    // that move: the field re-seeded from the stored HTML through
    // a trimming projection, and a later edit wrote the trimmed
    // text back (silent loss of the user's boundary spaces). The
    // state lives here, keyed by block position, so slot and ghost
    // share the very same field state and a remount is a non-event.
    val cellTextStates = remember { mutableMapOf<String, MutableState<String>>() }
    var activeSegmentId by remember { mutableStateOf<String?>(null) }
    var focusMode by remember { mutableStateOf(false) }
    var docStats by remember { mutableStateOf("") }

    var menuExpanded by remember { mutableStateOf(false) }
    var insertMenuExpanded by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }
    var linkUrl by remember { mutableStateOf("") }
    // Phase 6 (Editor Refinement #1): the link dialog's honesty
    // state (A10) — true when it was opened on an existing link, in
    // which case linkUrl starts prefilled and Remove link is
    // offered. Plus the two new insert dialogs: table size (A3)
    // and image-from-URL (A6) with its inline-error flag.
    var linkDialogExisting by remember { mutableStateOf(false) }
    var showTableDialog by remember { mutableStateOf(false) }
    var showImageUrlDialog by remember { mutableStateOf(false) }
    var imageUrl by remember { mutableStateOf("") }
    var imageUrlError by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var outlineItems by remember { mutableStateOf<List<OutlineEntry>>(emptyList()) }
    var showTemplateDialog by remember { mutableStateOf(false) }
    var showReminderDialog by remember { mutableStateOf(false) }
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
    // Phase 3.8: the drag SESSION state (see BlockDragGesture.kt).
    // While a session is live the document NEVER reorders and
    // neighbours never animate: the dragged block's content rides
    // a ghost overlay positioned by translation only, its list
    // slot renders as an empty placeholder of the same height,
    // and the only thing tracked is the insertion target. The
    // drop commits exactly one reorder + persist.
    var dragSession by remember { mutableStateOf<BlockDragSession?>(null) }
    // The pointer's current Y in list-viewport coordinates. Read
    // ONLY inside the ghost's graphicsLayer (a draw-phase read —
    // moving the finger never recomposes anything) and by the
    // session bookkeeping functions, never during composition.
    var dragPointerY by remember { mutableFloatStateOf(0f) }
    // The insertion target: an index into the COLLAPSED list (the
    // document with the dragged segment lifted out). The origin
    // position — a no-op drop — is the dragged segment's own index.
    var dragTargetIndex by remember { mutableIntStateOf(0) }
    // The segment index a surface down landed on, stashed by the
    // hit-test for the session start that follows a claim (the
    // pendingCaretFocusId pattern — hit-test and claim are two
    // calls for the same down, with no interleaving down between).
    var pendingDragIndex by remember { mutableIntStateOf(-1) }
    // Phase 3.8 (Codex review, PR #21): the ids of neighbours the
    // ghost has PASSED, accumulated across the whole session.
    // The target counts passed neighbours, but layoutInfo only
    // describes VISIBLE rows — on a long note under auto-scroll a
    // passed row scrolls out of the viewport and would stop
    // counting, walking the target backward while the finger
    // never moved. So crossings are remembered by identity: a
    // visible row joins its set when the edge condition holds
    // and leaves it when it visibly stops holding; an off-screen
    // row keeps its membership (it can only un-pass by sweeping
    // back past the ghost's edge, which makes it visible again
    // first). Plain mutable sets, not snapshot state — read and
    // written only inside the session bookkeeping, never during
    // composition.
    val passedAboveIds = remember { mutableSetOf<String>() }
    val passedBelowIds = remember { mutableSetOf<String>() }
    // The landing line's Y (list-viewport px), computed by the
    // session bookkeeping and held as state so the overlay can
    // read it INSIDE the line's graphicsLayer — a draw-phase
    // read. Computing it during composition instead would
    // subscribe the overlay to layoutInfo, and every auto-scroll
    // tick would recompose the ghost (Codex review, PR #21).
    val landingLineY = remember { mutableStateOf<Float?>(null) }
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
    val context = LocalContext.current
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
    val clipboard = LocalClipboardManager.current
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

    // ---- Phase 4.1: AI actions (Summarize / Suggest tags) --------
    // User-initiated only, confirm-before-apply: the model's output
    // lands in a sheet/dialog and touches the note only on Insert /
    // Apply. The in-flight call is a Job the progress dialog's
    // Cancel button cancels (which also cancels the HTTP request).
    // With no key saved for the active provider the service throws
    // NO_KEY, whose message points at Settings → AI features —
    // the same dialog shows mapped provider errors in calm form.
    var aiJob by remember { mutableStateOf<Job?>(null) }
    var aiBusyLabel by remember { mutableStateOf<String?>(null) }
    var aiNotice by remember { mutableStateOf<String?>(null) }
    // Beginner AI Setup #1 (F6): whether the current notice is a
    // setup gap (no key / provider not finished) — only those
    // notices offer the "Open AI settings" way out.
    var aiNoticeNeedsSetup by remember { mutableStateOf(false) }
    var aiSummary by remember { mutableStateOf<AiSummaryOutcome?>(null) }
    var aiTags by remember { mutableStateOf<AiTagOutcome?>(null) }

    fun runAiAction(label: String, action: suspend () -> Unit) {
        aiBusyLabel = label
        aiJob = scope.launch {
            try {
                action()
            } catch (e: AiException) {
                aiNoticeNeedsSetup = e.kind == AiErrorKind.NO_KEY ||
                    e.kind == AiErrorKind.NOT_CONFIGURED
                aiNotice = e.displayMessage
            } catch (e: CancellationException) {
                // The user's Cancel — nothing to report. Rethrow so
                // the coroutine ends as cancelled, not completed.
                throw e
            } finally {
                aiBusyLabel = null
                aiJob = null
            }
        }
    }

    fun startSummarize() {
        runAiAction("Summarize") {
            aiSummary = aiService.summarize(
                title = title,
                plainText = NoteDocument.plainText(syncSegments()),
            )
        }
    }

    fun startSuggestTags() {
        runAiAction("Suggest tags") {
            aiTags = aiService.suggestTags(
                title = title,
                plainText = NoteDocument.plainText(syncSegments()),
                existingTagNames = libraryTagNames,
                currentTagNames = parseTagsCsv(tagsCsv),
            )
        }
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
     *  a new slot — the landing is felt as well as seen. Phase 3.8:
     *  fired when the session's insertion TARGET changes (there
     *  are no mid-drag swaps anymore), so the tick rate is the
     *  target-change rate. */
    fun hapticTick() {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /**
     * Segment rows currently laid out, as (segment index, layout
     * info) pairs in document order. The title/meta/attachments
     * rows share the list, but their keys are not segment ids, so
     * they never appear here — a block moves among segments only.
     * All geometry below comes from this layout info (exact laid-
     * out quantities), never from the semantics tree.
     */
    fun visibleSegmentItems(): List<Pair<Int, LazyListItemInfo>> {
        val indexByKey = segments.mapIndexed { i, ui -> ui.id to i }.toMap()
        return listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            indexByKey[info.key]?.let { it to info }
        }
    }

    /**
     * Phase 3.8 hit-test for the surface gesture (see
     * BlockDragGesture.kt): is [position] (list-viewport
     * coordinates) on a DRAGGABLE segment — anything but a plain
     * text segment, whose long press is text selection? On a hit
     * the segment index is stashed in [pendingDragIndex] for
     * [beginDragSession]; the geometry itself is re-read there,
     * at claim time, so a hair of list movement during the press
     * cannot stale it.
     */
    fun dragHitTest(position: Offset): Boolean {
        if (!editing) return false
        val hit = visibleSegmentItems().firstOrNull { (index, info) ->
            segments[index].segment !is TextSegment &&
                position.y >= info.offset && position.y < info.offset + info.size
        } ?: return false
        pendingDragIndex = hit.first
        return true
    }

    /**
     * Phase 3.8: the landing line's Y (list-viewport coordinates)
     * for insertion target [target], or null when the boundary
     * cannot be placed from the visible rows. At the origin
     * target the line caps the dragged block's own slot; elsewhere
     * it sits in the gap above the segment that would follow the
     * dropped block — or below the last visible segment when the
     * target is past it. Called ONLY from [updateDragTarget]
     * (event/auto-scroll callbacks), never from composition —
     * see [landingLineY].
     */
    fun computeLandingLineY(target: Int): Float? {
        val session = dragSession ?: return null
        val spacingPx = with(density) { NotaSpacing.sm.toPx() }
        val items = visibleSegmentItems().toMap()
        val from = session.fromIndex
        if (target == from) {
            return items[from]?.let { it.offset - spacingPx / 2f }
        }
        // The collapsed item that would sit AT [target] after the
        // drop is the segment now at [target] (above the gap) or
        // [target] + 1 (below it).
        val following = if (target < from) target else target + 1
        items[following]?.let { return it.offset - spacingPx / 2f }
        // The target is past every visible segment: the line
        // trails the last visible one instead.
        val lastVisible = visibleSegmentItems()
            .lastOrNull { (index, _) -> index != from } ?: return null
        return lastVisible.second.offset + lastVisible.second.size + spacingPx / 2f
    }

    /**
     * Phase 3.8: recompute the insertion target for the live
     * session — called on every drag move and every auto-scroll
     * step. The rule is the 3.7 edge rule, re-targeted: the
     * document does not move, so instead of swapping, a neighbour
     * counts as PASSED once the ghost's leading edge has pushed
     * [BLOCK_SWAP_OVERSHOOT] past the neighbour's near edge —
     * ghost bottom vs the near edge of each block below, ghost
     * top vs the far edge of each block above, all read from the
     * list's own layoutInfo (exact laid-out offsets and sizes).
     * The target is the origin index, minus the blocks passed
     * above, plus the blocks passed below. Passed-ness is
     * accumulated BY SEGMENT ID across the session (see
     * [passedBelowIds]) so rows that scroll out of the viewport
     * under auto-scroll keep counting.
     *
     * Because the thresholds sit on the ghost's EDGES, the rule
     * is grab-point independent: at rest both edges sit inside
     * the block's own slot, nothing counts as passed, and the
     * target is the origin for any grab — including a deep grab
     * on a tall block over a short neighbour, where a
     * pointer/centre rule would retarget before the finger has
     * moved at all. Travel stays the short flick 3.7 shipped
     * (spacing + overshoot), whatever either block's height, and
     * the overshoot doubles as the hysteresis: un-passing needs
     * the full overshoot in retreat.
     *
     * The trailing caret tail clamps the target exactly as the
     * 3.7 swap guard did: the block may pass a real closing
     * paragraph (the commit restores the caret tail below it)
     * but never the empty caret segment itself.
     *
     * Every call also refreshes [landingLineY] — computed here,
     * in event/auto-scroll callbacks rather than composition, so
     * the overlay never subscribes to layoutInfo (see the state's
     * doc).
     */
    fun updateDragTarget() {
        val session = dragSession ?: return
        val overshootPx = with(density) { BLOCK_SWAP_OVERSHOOT.toPx() }
        val ghostTop = dragPointerY - session.grabOffsetPx
        val ghostBottom = ghostTop + session.blockHeightPx
        for ((index, info) in visibleSegmentItems()) {
            if (index == session.fromIndex) continue
            val id = segments[index].id
            if (index < session.fromIndex) {
                if (info.offset + info.size - overshootPx > ghostTop) {
                    passedAboveIds += id
                } else {
                    passedAboveIds -= id
                }
            } else {
                if (info.offset + overshootPx < ghostBottom) {
                    passedBelowIds += id
                } else {
                    passedBelowIds -= id
                }
            }
        }
        val collapsedCount = segments.size - 1
        val last = segments.lastOrNull()?.segment
        val maxTarget = if (last is TextSegment &&
            NoteDocument.isEmptyTextSegment(last)
        ) {
            collapsedCount - 1
        } else {
            collapsedCount
        }
        val newTarget = (session.fromIndex - passedAboveIds.size +
            passedBelowIds.size).coerceIn(0, maxTarget)
        if (newTarget != dragTargetIndex) {
            dragTargetIndex = newTarget
            hapticTick()
        }
        landingLineY.value = computeLandingLineY(newTarget)
    }

    /**
     * Phase 3.8: a surface long press claimed the block at
     * [pendingDragIndex] — open the session. The target starts at
     * the origin (a no-op drop), and any focus a block child (a
     * table cell) raised on the press is cleared: the session owns
     * the finger now, and a same-frame text selection/cursor must
     * not survive into it. Together with the surface gesture's
     * Initial-pass claim this is what makes a cell long press a
     * drag, always — a TAP still focuses and edits the cell.
     */
    fun beginDragSession(downPosition: Offset) {
        val index = pendingDragIndex
        val ui = segments.getOrNull(index) ?: return
        val info = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.key == ui.id } ?: return
        dragSession = BlockDragSession(
            segmentId = ui.id,
            fromIndex = index,
            grabOffsetPx = (downPosition.y - info.offset)
                .coerceIn(0f, info.size.toFloat()),
            blockHeightPx = info.size,
        )
        dragPointerY = downPosition.y
        dragTargetIndex = index
        passedAboveIds.clear()
        passedBelowIds.clear()
        focusManager.clearFocus()
        updateDragTarget()
    }

    /**
     * Phase 3.8: the finger lifted — commit the session with
     * EXACTLY ONE reorder (and one persist) when the target
     * differs from the origin. A no-move drop changes nothing and
     * persists nothing.
     */
    fun commitDragSession() {
        val session = dragSession ?: return
        dragSession = null
        landingLineY.value = null
        passedAboveIds.clear()
        passedBelowIds.clear()
        val from = segments.indexOfFirst { it.id == session.segmentId }
        if (from < 0) return
        // The list cannot change during a session, so [from] is
        // the session's origin and the target is already a valid
        // collapsed index; the coerce is belt-and-braces only.
        val target = dragTargetIndex.coerceIn(0, segments.size - 1)
        if (target == from) return
        segments = ensureTrailing(segments.moved(from, target))
        persistNow()
    }

    /**
     * Phase 3.8: the gesture was cancelled (the framework took
     * the pointer). The document was never touched during the
     * session, so there is nothing to undo and nothing to
     * persist — the session simply evaporates.
     */
    fun cancelDragSession() {
        dragSession = null
        landingLineY.value = null
        passedAboveIds.clear()
        passedBelowIds.clear()
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

    // Phase 5.4 dictation: one speech session per editor instance
    // (the screen is keyed by note id in the nav graph, so leaving a
    // note disposes its session — a transcript can never leak across
    // notes, and no recogniser runs behind the app). The mic is
    // offered only when the probe says a recogniser can actually
    // run; committed finals are inserted at the caret in order,
    // exactly once each, with 1.x's join rule (one space unless the
    // character before the caret is whitespace or a block start).
    val speechContext = LocalContext.current
    val speechEngine = remember(noteId) {
        app.nota.feature.voice.AndroidSpeechEngine(speechContext)
    }
    app.nota.feature.voice.rememberSpeechPermissionRequester(speechEngine)
    val speechController = remember(noteId) {
        app.nota.feature.voice.SpeechSessionController(speechEngine, scope)
    }
    var speechAvailable by remember(noteId) { mutableStateOf(false) }
    val speechSnapshot by speechController.snapshot.collectAsState()
    LaunchedEffect(speechController) {
        speechAvailable = app.nota.feature.voice.VoiceProbeUi.fromProbe(
            runCatching { speechEngine.probe() }.getOrNull(),
        ) is app.nota.feature.voice.VoiceAvailability.Available
    }
    DisposableEffect(speechController) {
        onDispose { speechController.cancel() }
    }
    // Phase 5.4 fix (Codex PR #31): leaving edit mode must end
    // dictation too. The toolbar and status strip unmount in read
    // mode, so a session left running here would keep recording
    // invisibly, with no way to stop it. Both exit paths — the ✓
    // button and the BackHandler — funnel through editing = false
    // (exitToRead), so keying on `editing` covers both. Entering
    // edit mode never starts anything.
    LaunchedEffect(editing) {
        if (!editing) speechController.cancel()
    }
    var insertedDictationSegments by remember(noteId) { mutableIntStateOf(0) }
    LaunchedEffect(speechSnapshot.committed) {
        val committed = speechSnapshot.committed
        while (insertedDictationSegments < committed.size) {
            // No caret right now (edit mode left mid-session): the
            // segment stays pending and drains when the next final
            // lands with a caret present.
            val st = activeState ?: break
            val segment = committed[insertedDictationSegments]
            insertedDictationSegments += 1
            val text = st.annotatedString.text
            val caret = st.selection.end.coerceIn(0, text.length)
            val beforeChar = if (caret > 0) text.substring(caret - 1, caret) else ""
            val insertion = app.nota.feature.voice.SpeechText
                .dictationInsertionText(beforeChar, segment)
            if (insertion.isNotEmpty()) {
                viewModel.insertDictatedText(insertion)
            }
        }
    }
    fun toggleDictation() {
        when (speechSnapshot.state) {
            app.nota.feature.voice.SpeechSessionState.LISTENING ->
                speechController.stop()
            app.nota.feature.voice.SpeechSessionState.IDLE,
            app.nota.feature.voice.SpeechSessionState.ERROR,
            -> speechController.start(Locale.getDefault().toLanguageTag())
            else -> Unit
        }
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

    // Phase 3.6 (G1) origin, Phase 3.8 form: auto-scroll while a
    // drag session rides the viewport's edge — the finger can hold
    // still at the edge while the document walks beneath it. In
    // the session model there is no translation to glue: the
    // pointer Y is a viewport quantity and does not change when
    // the list scrolls; what changes is the geometry the target
    // is computed from, so each consumed step simply recomputes
    // the target against the fresh layout. The step grows with
    // how deep into the edge zone the GHOST's visual edge sits;
    // the ghost itself never leaves the finger.
    LaunchedEffect(dragSession?.segmentId) {
        val session = dragSession ?: return@LaunchedEffect
        val zonePx = with(density) { 72.dp.toPx() }
        val maxStepPx = with(density) { 14.dp.toPx() }
        while (true) {
            delay(16)
            if (dragSession?.segmentId != session.segmentId) break
            val info = listState.layoutInfo
            val top = dragPointerY - session.grabOffsetPx
            val bottom = top + session.blockHeightPx
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
                    updateDragTarget()
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
                        // Phase 4.1: the two BYOK AI actions — edit
                        // mode only; they run on the unlocked
                        // content the editor already holds.
                        if (editing) {
                            DropdownMenuItem(
                                text = { Text("Summarize") },
                                leadingIcon = {
                                    Icon(Icons.Filled.AutoAwesome, contentDescription = null)
                                },
                                onClick = { menuExpanded = false; startSummarize() },
                            )
                            DropdownMenuItem(
                                text = { Text("Suggest tags") },
                                leadingIcon = {
                                    Icon(Icons.Filled.Sell, contentDescription = null)
                                },
                                onClick = { menuExpanded = false; startSuggestTags() },
                            )
                        }
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
                        // Phase 5.1: the note's reminder. Works even
                        // while the lock gate is up — setting one needs
                        // only the note's id + title, never its content.
                        if (current != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (reminder != null) {
                                            "Reminder · " + ReminderNotificationText
                                                .formatReminderTime(reminder!!.triggerAt)
                                        } else {
                                            "Set reminder"
                                        },
                                    )
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.Alarm, contentDescription = null)
                                },
                                onClick = {
                                    showReminderDialog = true
                                    menuExpanded = false
                                },
                            )
                        }
                        // Phase 5.2: pin this note to the home
                        // screen. Like the reminder above it needs
                        // only the note's id, title and lock state —
                        // a locked note pins under a generic label
                        // and opens through its lock gate, so it
                        // works while the gate is up too.
                        if (current != null) {
                            DropdownMenuItem(
                                text = { Text("Pin to home screen") },
                                leadingIcon = {
                                    Icon(Icons.Filled.AddToHomeScreen, contentDescription = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    val result = NotePinShortcut.requestPin(
                                        context,
                                        current.id,
                                        current.title,
                                        current.locked,
                                    )
                                    if (result == NotePinShortcut.Result.Unsupported) {
                                        Toast.makeText(
                                            context,
                                            "Your launcher doesn't support pinned shortcuts",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
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
        // Phase 3.8: one segment-editor body, two hosts — the
        // segment's list slot in normal flow, and the drag ghost
        // overlay while a session holds the segment. Factoring the
        // call keeps the ghost a pixel-exact copy of the slot it
        // left. (The rail stays a visual affordance; the drag
        // gesture itself lives on the surface, below.)
        val segmentEditor: @Composable (UiSegment, Int) -> Unit = { ui, index ->
            val isBlock = ui.segment !is TextSegment && ui.segment !is QuoteSegment
            SegmentEditor(
                ui = ui,
                isActive = ui.id == activeSegmentId,
                isTrailingCaret = index == segments.lastIndex &&
                    ui.segment is TextSegment,
                // Phase 6 (Editor Refinement #1): "Start writing…"
                // belongs to the note's FIRST text segment only —
                // later empty segments are working space, not the
                // start of the note (see SegmentEditor). First
                // TEXT segment, not document index 0 (Codex PR #34
                // P3): a note that starts with a block — an image
                // inserted into an empty note — has its first text
                // segment at index 1 and must still show it.
                showBodyPlaceholder = ui.segment is TextSegment &&
                    segments.take(index).none { it.segment is TextSegment },
                trailingFocusRequester = trailingFocusRequester,
                focusRequester = segmentFocusRequesters
                    .getOrPut(ui.id) { FocusRequester() },
                stateFor = { richStateFor(ui) },
                cellTextStates = cellTextStates,
                onFocus = { activeSegmentId = ui.id },
                onChange = { updateBlock(ui.id, it) },
                onRemove = { removeBlock(ui.id) },
                attachments = attachments,
                fileFor = { viewModel.attachmentFile(it) },
                bringIntoView = bringIntoView,
                onPinchingChange = { imagePinchActive = it },
                dragHandleModifier = if (isBlock) Modifier else null,
            )
        }
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            // Phase 3.8: the drag surface wraps the list — it
            // hit-tests every down, claims block long presses in
            // the Initial pass, and hosts the session overlays
            // (landing line + ghost) above the list. The box and
            // the list share one origin, so surface coordinates
            // ARE list-viewport coordinates throughout.
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .blockDragSurface(
                        key = editing,
                        hitTest = { position -> dragHitTest(position) },
                        onSessionStart = { down -> beginDragSession(down) },
                        onSessionMove = { pointerY ->
                            dragPointerY = pointerY
                            updateDragTarget()
                        },
                        onSessionEnd = { commitDragSession() },
                        onSessionCancel = { cancelDragSession() },
                    ),
            ) {
            LazyColumn(
                state = listState,
                // Phase 3.5 (D4): while a block drag is live the list
                // itself must not start scrolling under the gesture.
                // Phase 3.6 (G4): the same while an image pinch is live.
                // (Auto-scroll during a drag is programmatic scrollBy,
                // which userScrollEnabled does not gate.)
                userScrollEnabled = dragSession == null && !imagePinchActive,
                modifier = Modifier.fillMaxSize(),
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
                    // does a quote — by long-press on the block itself;
                    // only plain text segments are exempt, because
                    // their long-press is text selection. In 3.8 the
                    // gesture lives on the surface wrapping this list
                    // (see BlockDragGesture.kt), and a live session
                    // changes exactly one thing here: the dragged
                    // segment's slot renders as an EMPTY PLACEHOLDER
                    // holding the block's height, while its content
                    // rides the ghost overlay. The document under the
                    // finger does not reorder, animate or recompose
                    // for the whole session — the drop is the single
                    // moment anything moves.
                    val session = dragSession
                    if (session != null && session.segmentId == ui.id) {
                        Box(
                            Modifier.fillMaxWidth()
                                .height(with(density) { session.blockHeightPx.toDp() }),
                        )
                        return@itemsIndexed
                    }
                    segmentEditor(ui, index)
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
                if (links.isNotEmpty()) {
                    item(key = "links") {
                        Column {
                            Text(
                                "Links",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = NotaSpacing.sm),
                            )
                            links.forEach { target ->
                                TextButton(
                                    onClick = {
                                        retirePendingInsert()
                                        persistNow { onOpenNote(target.id) }
                                    },
                                ) {
                                    Text(target.title.ifBlank { "Untitled note" })
                                }
                            }
                        }
                    }
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
            // Phase 3.8: the session overlays, drawn above the
            // list inside the drag surface's box, in list-viewport
            // coordinates: first the landing line at the current
            // insertion target, then the GHOST — the dragged
            // block's own editor, composed once when the session
            // starts and afterwards moved ONLY by the graphicsLayer
            // translation below (a draw-phase read of the pointer
            // state, so the block's content never recomposes while
            // the finger moves). On drop the session state clears
            // and the real item composes in its committed place in
            // the same frame — the ghost snaps, never tweens, so
            // the settle can never fight the commit.
            val session = dragSession
            if (session != null) {
                val draggedUi = segments.firstOrNull { it.id == session.segmentId }
                if (draggedUi != null) {
                    // The landing line: its Y was computed by the
                    // session bookkeeping (updateDragTarget) and is
                    // read here INSIDE the graphicsLayer — a
                    // draw-phase read, so an auto-scroll tick moves
                    // the line without recomposing anything.
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = NotaSpacing.md)
                            .graphicsLayer {
                                val y = landingLineY.value
                                translationY = y ?: 0f
                                alpha = if (y == null) 0f else 1f
                            }
                            .height(3.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(2.dp),
                            ),
                    )
                    Box(
                        Modifier.fillMaxWidth()
                            .padding(horizontal = NotaSpacing.md)
                            .graphicsLayer {
                                translationY = dragPointerY - session.grabOffsetPx
                                alpha = 0.92f
                                shadowElevation = with(density) { 8.dp.toPx() }
                            },
                    ) {
                        segmentEditor(draggedUi, session.fromIndex)
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
            // Phase 5.4: the dictation status strip, directly above
            // the toolbar where the link suggestions also live —
            // "Listening…" + the live partial while dictating, or
            // the calm error line with a dismiss once it ends badly.
            if (editing && speechSnapshot.state !=
                app.nota.feature.voice.SpeechSessionState.IDLE
            ) {
                val speechError = speechSnapshot.errorKind?.takeIf {
                    speechSnapshot.state ==
                        app.nota.feature.voice.SpeechSessionState.ERROR
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = NotaSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            speechError != null ->
                                app.nota.feature.voice.SpeechErrorLines
                                    .line(speechError)
                            speechSnapshot.state ==
                                app.nota.feature.voice.SpeechSessionState
                                    .REQUESTING_PERMISSION ->
                                "Preparing the microphone…"
                            speechSnapshot.state ==
                                app.nota.feature.voice.SpeechSessionState
                                    .STOPPING ->
                                "Finishing…"
                            speechSnapshot.partial.isNotEmpty() ->
                                speechSnapshot.partial
                            else -> "Listening…"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (speechError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontStyle = if (speechError == null &&
                            speechSnapshot.partial.isNotEmpty()
                        ) {
                            androidx.compose.ui.text.font.FontStyle.Italic
                        } else {
                            androidx.compose.ui.text.font.FontStyle.Normal
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (speechError != null) {
                        IconButton(
                            onClick = {
                                speechController.reset()
                                insertedDictationSegments = 0
                            },
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Dismiss",
                            )
                        }
                    }
                }
            }
            if (editing) {
                EditorToolbar(
                    state = activeState,
                    modifier = Modifier.alpha(chromeAlpha),
                    micVisible = speechAvailable,
                    micListening = speechSnapshot.state ==
                        app.nota.feature.voice.SpeechSessionState.LISTENING,
                    micBusy = speechSnapshot.state ==
                        app.nota.feature.voice.SpeechSessionState
                            .REQUESTING_PERMISSION ||
                        speechSnapshot.state ==
                        app.nota.feature.voice.SpeechSessionState.STOPPING,
                    onMicClick = { toggleDictation() },
                    insertMenuExpanded = insertMenuExpanded,
                    onInsertMenuChange = { insertMenuExpanded = it },
                    onLinkRequest = {
                        // Phase 6 (Editor Refinement #1, A10): the
                        // dialog opens PREFILLED when the caret sits
                        // in an existing link, and knows it is an
                        // edit (Remove link is offered) rather than a
                        // fresh insert.
                        val existing = activeState?.toolbarFormatState()?.linkUrl
                        linkUrl = existing ?: ""
                        linkDialogExisting = existing != null
                        showLinkDialog = true
                    },
                    onInsertTableRequest = { showTableDialog = true },
                    onInsertImageUrl = {
                        imageUrl = ""
                        imageUrlError = false
                        showImageUrlDialog = true
                    },
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
        // Phase 6 (Editor Refinement #1, A10): one dialog, honest
        // about which job it is doing. Opened on an existing link it
        // is an EDIT — the field starts with that link's URL (see
        // onLinkRequest), Save rewrites the link in place via the
        // library's updateLink, and Remove link unwraps it keeping
        // the text. Opened anywhere else it is the original insert.
        AlertDialog(
            onDismissRequest = { showLinkDialog = false },
            title = { Text(if (linkDialogExisting) "Edit link" else "Insert link") },
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
                        when {
                            linkDialogExisting -> st.updateLink(url)
                            st.selection.collapsed -> st.addLink(text = url, url = url)
                            else -> st.addLinkToSelection(url)
                        }
                    }
                    linkUrl = ""
                    showLinkDialog = false
                }) { Text(if (linkDialogExisting) "Save" else "Insert") }
            },
            dismissButton = {
                Row {
                    if (linkDialogExisting) {
                        TextButton(onClick = {
                            activeState?.removeLink()
                            linkUrl = ""
                            showLinkDialog = false
                        }) { Text("Remove link") }
                    }
                    TextButton(onClick = { showLinkDialog = false }) { Text("Cancel") }
                }
            },
        )
    }

    if (showTableDialog) {
        TableInsertDialog(
            onDismiss = { showTableDialog = false },
            onInsert = { rows, cols, headerRow ->
                showTableDialog = false
                // The insert menu's placement is unchanged: the new
                // table lands after the active segment, exactly
                // where the old fixed 2×2 insert put it.
                insertBlock(TableOps.newTable(rows, cols, headerRow))
            },
        )
    }

    if (showImageUrlDialog) {
        // Phase 6 (Editor Refinement #1, A6): image from URL. The
        // URL is validated by parseRemoteImageUrl; an invalid one
        // shows the inline error and inserts NOTHING. A valid one
        // becomes a remote ImageSegment at the caret through the
        // same insertBlockAtCaret funnel the photo picker's insert
        // ends in (Phase 3.5 discipline) — no attachment is created
        // for a remote image.
        AlertDialog(
            onDismissRequest = { showImageUrlDialog = false },
            title = { Text("Image from URL") },
            text = {
                Column {
                    OutlinedTextField(
                        value = imageUrl,
                        onValueChange = {
                            imageUrl = it
                            imageUrlError = false
                        },
                        label = { Text("Image URL") },
                        singleLine = true,
                        isError = imageUrlError,
                        modifier = Modifier.fillMaxWidth().testTag("imageUrlField"),
                    )
                    if (imageUrlError) {
                        Text(
                            "Enter a valid http(s) image URL",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = parseRemoteImageUrl(imageUrl)
                    if (parsed == null) {
                        imageUrlError = true
                    } else {
                        showImageUrlDialog = false
                        insertBlockAtCaret(ImageSegment(src = parsed))
                    }
                }) { Text("Insert") }
            },
            dismissButton = {
                TextButton(onClick = { showImageUrlDialog = false }) { Text("Cancel") }
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

    // Phase 4.1 AI action surfaces (see AiActionSheets.kt):
    // progress-with-cancel while a call is in flight, the no-key /
    // error notice, and the two confirm-before-apply result
    // surfaces. Insert and Apply are the only paths by which model
    // output ever reaches the note.
    if (aiBusyLabel != null) {
        AiProgressDialog(label = aiBusyLabel!!, onCancel = { aiJob?.cancel() })
    }
    aiNotice?.let { message ->
        AiNoticeDialog(
            message = message,
            onDismiss = { aiNotice = null },
            onOpenSettings = if (aiNoticeNeedsSetup) {
                {
                    aiNotice = null
                    onOpenAiSettings()
                }
            } else {
                null
            },
        )
    }
    aiSummary?.let { outcome ->
        AiSummarySheet(
            summary = outcome.summary,
            truncated = outcome.truncated,
            onInsertAtTop = {
                segments = ensureTrailing(
                    listOf(
                        UiSegment(newUiId(), TextSegment(AiSummaryHtml.toHtml(outcome.summary))),
                    ) + segments,
                )
                persistNow()
                aiSummary = null
            },
            onCopy = {
                clipboard.setText(AnnotatedString(outcome.summary))
                aiSummary = null
            },
            onDismiss = { aiSummary = null },
        )
    }
    aiTags?.let { outcome ->
        AiTagsDialog(
            tags = outcome.tags,
            truncated = outcome.truncated,
            onApply = { chosen ->
                val merged = (parseTagsCsv(tagsCsv) + chosen)
                    .distinctBy { it.trim().lowercase() }
                tagsCsv = merged.joinToString(", ")
                persistNow()
                aiTags = null
            },
            onDismiss = { aiTags = null },
        )
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

    if (showReminderDialog) {
        val noteForReminder = current
        if (noteForReminder != null) {
            ReminderDialog(
                noteTitle = noteForReminder.title,
                existing = reminder,
                onDismiss = { showReminderDialog = false },
                onSave = { triggerAt, alertType ->
                    viewModel.setReminder(triggerAt, alertType)
                },
                onRemove = if (reminder != null) {
                    { viewModel.removeReminder() }
                } else {
                    null
                },
            )
        }
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
    // Phase 6 (Editor Refinement #1): true only for the note's
    // first text segment — an empty body there shows the
    // "Start writing…" placeholder (1.x parity; the foundation
    // editor has no placeholder slot, so it is an overlay that is
    // never part of the document and can never be serialised).
    showBodyPlaceholder: Boolean = false,
    trailingFocusRequester: FocusRequester? = null,
    // Phase 3.5 (D4): per-segment focus requester (caret placement
    // after a split-insert) and the block drag handle's gesture
    // modifier (null for text/quote segments, which never drag).
    focusRequester: FocusRequester? = null,
    dragHandleModifier: Modifier? = null,
    stateFor: () -> RichTextState,
    // Phase 3.8 (Codex finding 3): the screen-owned cell/item field
    // text states, hoisted above this editor so the list slot and
    // the drag ghost share them (see the screen's declaration).
    cellTextStates: MutableMap<String, MutableState<String>>,
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
            Box(Modifier.fillMaxWidth()) {
                BasicRichTextEditor(
                    state = state,
                    textStyle = editorTextStyle,
                    modifier = editorModifier,
                )
                if (showBodyPlaceholder && state.annotatedString.text.isEmpty()) {
                    Text(
                        "Start writing…",
                        style = editorTextStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                    modifier = Modifier.weight(1f).then(
                        if (focusRequester != null) {
                            Modifier.focusRequester(focusRequester)
                        } else {
                            Modifier
                        },
                    ),
                )
                // Phase 6 (Editor Refinement #1 fix): every block
                // must be deletable — a quote previously had NO
                // removal path at all (its onRemove went unwired,
                // and emptying the text leaves a quote segment that
                // round-trips forever). The ✕ sits inline at the end
                // of the quote's own row, the DividerSegment pattern,
                // and flows through the same removeBlock as every
                // other block's remove affordance.
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove quote")
                }
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
                        is TableSegment -> TableBlock(ui.id, segment, cellTextStates, onChange, onRemove)
                        is TaskListSegment -> TaskListBlock(ui.id, segment, cellTextStates, onChange, onRemove)
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
                        // Phase 6 (Editor Refinement #1 fix): the
                        // code field previously rendered BARE — its
                        // onRemove went unwired, so a code block could
                        // never be removed once inserted (Jubayer's
                        // device-test bug; his ruling: every block is
                        // deletable). It now renders inside BlockShell,
                        // the exact shell File/Chart/Opaque use, whose
                        // ✕ ("Remove block") flows through removeBlock.
                        // The field itself — monospace style, minLines,
                        // focus modifier, write-through — is unchanged.
                        is CodeSegment -> BlockShell(onRemove) {
                            OutlinedTextField(
                                value = segment.text,
                                onValueChange = { onChange(CodeSegment(it)) },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                modifier = Modifier.fillMaxWidth().then(focusModifier),
                                minLines = 2,
                            )
                        }
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
 * A single-line cell/item field. Writing back through the segment
 * on every keystroke is fine, but rendering from the segment is
 * not: the plain-text projection trims and normalises, which would
 * eat spaces while typing. So the field's text state is HOISTED
 * into the screen-owned [cellTextStates] map, keyed by block
 * position and only seeded (losslessly — see
 * [app.nota.data.text.PlainTextCodec.htmlToCellText]) from the
 * stored content on first composition. The hoist matters beyond
 * typing: over a Phase 3.8 drag the block's editor composes in
 * its list slot, then in the session ghost, then in the slot
 * again — state held in a local remember() would be discarded at
 * the first move and the field would re-seed trimmed (Codex
 * finding 3, PR #21). In the hoisted map the same state object
 * serves every composition site, so a remount is a non-event.
 * The one event the hoist must NOT survive is a structural
 * edit to the block itself — see [evictCellTextStates].
 */

/**
 * Phase 3.8 follow-up (Codex P2, PR #21): the hoisted field
 * states are keyed by POSITION inside their block ("<block>-r-c"
 * for table cells, "<block>-item-i" for task items), so when a
 * block's structure changes — a task item or a table row/column
 * added or removed — the surviving keys no longer name the
 * fields they were seeded for. Worst case, a REMOVED item's
 * state outlives it: a fresh item added at the same index would
 * inherit the deleted text in its field, and a later edit would
 * write that text back into the document. The structural
 * handlers in TableBlock / TaskListBlock therefore evict the
 * block's entries before applying the change. Every field then
 * re-seeds losslessly from the segment — which the
 * per-keystroke write-through keeps in sync — so the eviction
 * is invisible except that stale text can no longer resurrect.
 * (Block ids are UUIDs, so the "<blockId>-" prefix cannot
 * collide with another block's keys.)
 */
private fun evictCellTextStates(
    cellTextStates: MutableMap<String, MutableState<String>>,
    blockId: String,
) {
    val prefix = "$blockId-"
    cellTextStates.keys.removeAll { it.startsWith(prefix) }
}
@Composable
private fun EditableCellText(
    key: String,
    initial: String,
    bodyStyle: Boolean,
    cellTextStates: MutableMap<String, MutableState<String>>,
    modifier: Modifier = Modifier,
    // Phase 6 (Editor Refinement #1): focus-gain hook — TableBlock
    // tracks its selected cell (the target of the at-cell row/column
    // operations) from it. Fires on gain only; losing focus to an
    // operation button must not clear the selection first.
    onFocus: () -> Unit = {},
    onEdit: (String) -> Unit,
) {
    val textState = cellTextStates.getOrPut(key) { mutableStateOf(initial) }
    OutlinedTextField(
        value = textState.value,
        onValueChange = {
            textState.value = it
            onEdit(it)
        },
        singleLine = true,
        textStyle = if (bodyStyle) {
            MaterialTheme.typography.bodyMedium
        } else {
            MaterialTheme.typography.bodySmall
        },
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
    )
}

@Composable
private fun TableBlock(
    blockId: String,
    table: TableSegment,
    cellTextStates: MutableMap<String, MutableState<String>>,
    onChange: (DocSegment) -> Unit,
    onRemove: () -> Unit,
) {
    // Phase 6 (Editor Refinement #1, A3): the cell the at-cell
    // row/column operations act on — set when a cell field gains
    // focus, cleared after every structural change (the change
    // evicts the block's field states anyway, and a stale
    // coordinate would aim the next operation at shifted content).
    var selectedCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }
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
                            // Field state is hoisted (see
                            // EditableCellText): projecting through
                            // the codec on every frame — or reseeding
                            // on a drag remount — would eat spaces.
                            // The seed is the lossless projection.
                            key = "$blockId-$r-$c",
                            initial = PlainTextCodec.htmlToCellText(cell),
                            bodyStyle = false,
                            cellTextStates = cellTextStates,
                            modifier = Modifier.weight(1f),
                            onFocus = { selectedCell = r to c },
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
            // Phase 6 (Editor Refinement #1, A3): operations AT the
            // selected cell — 1.x's contextual table bar, in the
            // footer idiom of this block. Every operation goes
            // through TableOps (contents and spans stay parallel)
            // and the same eviction discipline as the append/drop
            // handlers below, then clears the selection: the fields
            // re-seed after eviction, and a stale coordinate would
            // aim at shifted content.
            val sel = selectedCell
            if (sel != null) {
                val (sr, sc) = sel
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(NotaSpacing.xs),
                ) {
                    TextButton(onClick = {
                        evictCellTextStates(cellTextStates, blockId)
                        onChange(TableOps.insertRow(table, sr))
                        selectedCell = null
                    }) { Text("Row above") }
                    TextButton(onClick = {
                        evictCellTextStates(cellTextStates, blockId)
                        onChange(TableOps.insertRow(table, sr + 1))
                        selectedCell = null
                    }) { Text("Row below") }
                    TextButton(onClick = {
                        evictCellTextStates(cellTextStates, blockId)
                        onChange(TableOps.insertColumn(table, TableOps.visualColumnOf(table, sr, sc)))
                        selectedCell = null
                    }) { Text("Col left") }
                    TextButton(onClick = {
                        evictCellTextStates(cellTextStates, blockId)
                        onChange(
                            TableOps.insertColumn(
                                table,
                                TableOps.visualColumnOf(table, sr, sc) +
                                    TableOps.colSpanOf(table, sr, sc),
                            ),
                        )
                        selectedCell = null
                    }) { Text("Col right") }
                    if (table.rows.size > 1) {
                        TextButton(onClick = {
                            evictCellTextStates(cellTextStates, blockId)
                            onChange(TableOps.deleteRow(table, sr))
                            selectedCell = null
                        }) { Text("Delete row") }
                    }
                    if (TableOps.visualColumns(table) > 1) {
                        TextButton(onClick = {
                            evictCellTextStates(cellTextStates, blockId)
                            onChange(
                                TableOps.deleteColumn(
                                    table,
                                    TableOps.visualColumnOf(table, sr, sc),
                                ),
                            )
                            selectedCell = null
                        }) { Text("Delete col") }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NotaSpacing.xs)) {
                TextButton(onClick = {
                    evictCellTextStates(cellTextStates, blockId)
                    selectedCell = null
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
                    evictCellTextStates(cellTextStates, blockId)
                    selectedCell = null
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
                        evictCellTextStates(cellTextStates, blockId)
                        selectedCell = null
                        // Through TableOps like "Delete row": a
                        // rowspan crossing the dropped row must
                        // shrink, not outlive it.
                        onChange(TableOps.deleteRow(table, table.rows.size - 1))
                    }) { Text("− Row") }
                }
                if (TableOps.visualColumns(table) > 1) {
                    TextButton(onClick = {
                        evictCellTextStates(cellTextStates, blockId)
                        selectedCell = null
                        // Through TableOps like "Delete col": a
                        // trailing MERGED cell must shrink, not be
                        // dropped whole with its content.
                        onChange(
                            TableOps.deleteColumn(
                                table,
                                TableOps.visualColumns(table) - 1,
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
    cellTextStates: MutableMap<String, MutableState<String>>,
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
                        initial = PlainTextCodec.htmlToCellText(item.contentHtml),
                        bodyStyle = true,
                        cellTextStates = cellTextStates,
                        modifier = Modifier.weight(1f),
                        onEdit = { newText ->
                            onChange(
                                TaskListSegment(
                                    list.items.mapIndexed { i, old ->
                                        if (i == index) {
                                            // Phase 6 polish (N5): the
                                            // label edit must not flatten
                                            // the item's inline formatting
                                            // (the old path rebuilt the
                                            // item as escaped plain text).
                                            old.copy(
                                                contentHtml = NoteDocument
                                                    .replaceTaskItemText(old.contentHtml, newText),
                                            )
                                        } else {
                                            old
                                        }
                                    },
                                ),
                            )
                        },
                    )
                    IconButton(onClick = {
                        evictCellTextStates(cellTextStates, blockId)
                        onChange(TaskListSegment(list.items.filterIndexed { i, _ -> i != index }))
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove item")
                    }
                }
            }
            Row {
                TextButton(onClick = {
                    evictCellTextStates(cellTextStates, blockId)
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
    // Phase 6 (Editor Refinement #1, B1): the edit-mode image wears
    // NO surfaceVariant card and NO always-on alt header any more —
    // it renders clean, exactly as read mode renders it, and the
    // chrome (selection border on the image itself, the existing
    // width row, remove) appears only while the block is selected.
    // The pinch gesture and the width drag handle below are
    // untouched, and how width persists (the width="N%" attribute)
    // does not change.
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val model: Any? = when {
            attachment != null -> fileFor(attachment)
            image.src.isNotBlank() -> image.src
            else -> null
        }
        if (model != null) {
                // The min height keeps a body on the block even
                // when the painter has no size (an unloadable
                // source lays out at zero height, and a
                // zero-height block could not be tapped, selected
                // or drag-grabbed — the old card's header masked
                // exactly that). A loadable image taller than the
                // floor is unaffected.
                Box(
                    Modifier.fillMaxWidth()
                        .heightIn(min = 96.dp)
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
                            .then(
                                if (selected) {
                                    Modifier.border(
                                        2.dp,
                                        MaterialTheme.colorScheme.primary,
                                        RoundedCornerShape(8.dp),
                                    )
                                } else {
                                    Modifier
                                },
                            )
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
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove image")
                        }
                    }
                }
        } else {
            // No renderable source (a blank src with no matching
            // attachment): keep a slim, removable placeholder row
            // rather than the old card header's remove button.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Image unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove image")
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

/**
 * Phase 6 (Editor Refinement #1, A2): the text-colour palette,
 * copied VERBATIM from Noma 1.x's `TEXT_COLORS` (note-editor.tsx).
 * The hexes ARE the shared HTML format (a span's `color:` style),
 * so both apps render the same note identically — do not invent
 * values here. 1.x's remaining entry is "Default" (null), the
 * clear action, which the toolbar renders as its own menu row.
 * (Order and names are 1.x's too.)
 */
internal val TEXT_COLOR_SWATCHES: List<Pair<String, Long>> = listOf(
    "Ink" to 0xFF1F1B16,
    "Slate" to 0xFF4B5563,
    "Red" to 0xFFB3261E,
    "Amber" to 0xFFB45309,
    "Green" to 0xFF15803D,
    "Blue" to 0xFF1D4ED8,
    "Violet" to 0xFF6D28D9,
)

/**
 * Ditto for highlight/background colour — 1.x's `BG_COLORS`,
 * stored the same way (a span's `background:` style). 1.x's clear
 * entry is "None" (null), rendered as the menu's own row.
 */
internal val HIGHLIGHT_COLOR_SWATCHES: List<Pair<String, Long>> = listOf(
    "Sand" to 0xFFF4EAD9,
    "Rose" to 0xFFFBE0E0,
    "Peach" to 0xFFFDE4CF,
    "Lemon" to 0xFFFBF1C7,
    "Mint" to 0xFFDCF3E4,
    "Sky" to 0xFFDDE9FB,
    "Lilac" to 0xFFE8E0FB,
)

/**
 * Phase 6 (Editor Refinement #1, A6): the Image-from-URL dialog's
 * gate. Returns the trimmed URL when it is an absolute http(s) URL
 * with something after the scheme and no whitespace; null for
 * anything else (blank, scheme-less, another scheme, a bare
 * scheme) — the dialog shows its inline error and inserts nothing.
 */
internal fun parseRemoteImageUrl(raw: String): String? {
    val url = raw.trim()
    if (url.isEmpty() || url.any { it.isWhitespace() }) return null
    val schemeLength = when {
        url.startsWith("https://", ignoreCase = true) -> "https://".length
        url.startsWith("http://", ignoreCase = true) -> "http://".length
        else -> return null
    }
    return if (url.length > schemeLength) url else null
}

/** Phase 3.6 (G2): how much of the target segment's plain text a
 *  staged picker record keeps as its identity anchor. */
private const val INSERT_ANCHOR_CHARS = 60

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

/**
 * Phase 6 (Editor Refinement #1, A3): the insert-table dialog —
 * 1.x's shape (Rows / Columns number fields with its caps, an
 * "Include header row" checkbox, 3×3 with header as the defaults)
 * in Nota's dialog idiom. Dimensions are clamped by
 * [TableOps.newTable] on insert, so out-of-range input can never
 * mint an oversized table.
 */
@Composable
private fun TableInsertDialog(
    onDismiss: () -> Unit,
    onInsert: (rows: Int, cols: Int, headerRow: Boolean) -> Unit,
) {
    var rowsText by remember { mutableStateOf("3") }
    var colsText by remember { mutableStateOf("3") }
    var headerRow by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert table") },
        text = {
            Column {
                OutlinedTextField(
                    value = rowsText,
                    onValueChange = { rowsText = it.filter { ch -> ch.isDigit() }.take(2) },
                    label = { Text("Rows (max ${TableOps.MAX_ROWS})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("tableRowsField"),
                )
                Spacer(Modifier.height(NotaSpacing.sm))
                OutlinedTextField(
                    value = colsText,
                    onValueChange = { colsText = it.filter { ch -> ch.isDigit() }.take(2) },
                    label = { Text("Columns (max ${TableOps.MAX_COLS})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().testTag("tableColsField"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = headerRow, onCheckedChange = { headerRow = it })
                    Text("Include header row")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onInsert(
                    rowsText.toIntOrNull() ?: 3,
                    colsText.toIntOrNull() ?: 3,
                    headerRow,
                )
            }) { Text("Insert") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalRichTextApi::class)
@Composable
private fun EditorToolbar(
    state: RichTextState?,
    modifier: Modifier = Modifier,
    micVisible: Boolean = false,
    micListening: Boolean = false,
    micBusy: Boolean = false,
    onMicClick: () -> Unit = {},
    insertMenuExpanded: Boolean,
    onInsertMenuChange: (Boolean) -> Unit,
    onLinkRequest: () -> Unit,
    onInsertImage: () -> Unit,
    onInsertImageUrl: () -> Unit,
    onInsertFile: () -> Unit,
    onInsertTableRequest: () -> Unit,
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
        // Phase 5.4: the dictation mic leads the toolbar — 1.x puts
        // its mic in the editor toolbar too. It renders ONLY when
        // the availability probe passed (never a dead button);
        // listening wears the selected pill, the in-between states
        // show a spinner and take no taps.
        if (micVisible) {
            IconButton(
                onClick = onMicClick,
                enabled = enabled && !micBusy,
                colors = toolbarIconColors(micListening),
            ) {
                if (micBusy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = if (micListening) {
                            "Stop dictation"
                        } else {
                            "Dictate"
                        },
                    )
                }
            }
        }
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
        // Phase 6 (Editor Refinement #1, A2): 1.x's colour palettes
        // replace the 3-swatch stub and the one fixed yellow. Each
        // menu holds 1.x's colours (TEXT_COLOR_SWATCHES /
        // HIGHLIGHT_COLOR_SWATCHES — verbatim hexes) plus its clear
        // entry ("Default" / "None"). Applying goes through the
        // same toggleSpanStyle the old swatches used: tapping the
        // ACTIVE colour also clears it, and the clear entries
        // toggle the active colour off explicitly — or, for a
        // MIXED-colour selection (no single active colour), clear
        // the field across the whole selection via
        // clearTextColorInSelection / clearHighlightInSelection
        // (Codex PR #34 P2).
        var textColorMenuExpanded by remember { mutableStateOf(false) }
        Box {
            IconButton(
                onClick = { textColorMenuExpanded = true },
                enabled = enabled,
                colors = toolbarIconColors(
                    format?.textColor != null || format?.textColorMixed == true,
                ),
            ) {
                Icon(Icons.Filled.FormatColorText, contentDescription = "Text colour")
            }
            DropdownMenu(
                expanded = textColorMenuExpanded,
                onDismissRequest = { textColorMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Default") },
                    leadingIcon = {
                        Icon(Icons.Filled.FormatColorReset, contentDescription = null)
                    },
                    trailingIcon = {
                        // The check means "no colour in play" —
                        // a MIXED selection has colour in play, so
                        // it must not wear the check (Codex P2).
                        if (format?.textColor == null &&
                            format?.textColorMixed != true
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        textColorMenuExpanded = false
                        val current = format?.textColor
                        if (current != null) {
                            state?.toggleSpanStyle(SpanStyle(color = current))
                        } else if (format?.textColorMixed == true) {
                            state?.clearTextColorInSelection()
                        }
                    },
                )
                TEXT_COLOR_SWATCHES.forEach { (name, argb) ->
                    val color = Color(argb)
                    DropdownMenuItem(
                        text = { Text(name) },
                        leadingIcon = {
                            Swatch(argb, selected = format?.textColor == color)
                        },
                        onClick = {
                            textColorMenuExpanded = false
                            state?.toggleSpanStyle(SpanStyle(color = color))
                        },
                    )
                }
            }
        }
        var highlightMenuExpanded by remember { mutableStateOf(false) }
        Box {
            IconButton(
                onClick = { highlightMenuExpanded = true },
                enabled = enabled,
                colors = toolbarIconColors(
                    format?.highlightColor != null || format?.highlightMixed == true,
                ),
            ) {
                Icon(Icons.Filled.FormatColorFill, contentDescription = "Highlight colour")
            }
            DropdownMenu(
                expanded = highlightMenuExpanded,
                onDismissRequest = { highlightMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("None") },
                    leadingIcon = {
                        Icon(Icons.Filled.FormatColorReset, contentDescription = null)
                    },
                    trailingIcon = {
                        if (format?.highlightColor == null &&
                            format?.highlightMixed != true
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        highlightMenuExpanded = false
                        val current = format?.highlightColor
                        if (current != null) {
                            state?.toggleSpanStyle(SpanStyle(background = current))
                        } else if (format?.highlightMixed == true) {
                            state?.clearHighlightInSelection()
                        }
                    },
                )
                HIGHLIGHT_COLOR_SWATCHES.forEach { (name, argb) ->
                    val color = Color(argb)
                    DropdownMenuItem(
                        text = { Text(name) },
                        leadingIcon = {
                            Swatch(argb, selected = format?.highlightColor == color)
                        },
                        onClick = {
                            highlightMenuExpanded = false
                            state?.toggleSpanStyle(SpanStyle(background = color))
                        },
                    )
                }
            }
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
                    text = { Text("Image from URL…") },
                    leadingIcon = { Icon(Icons.Filled.Language, contentDescription = null) },
                    onClick = { onInsertMenuChange(false); onInsertImageUrl() },
                )
                DropdownMenuItem(
                    text = { Text("File attachment…") },
                    leadingIcon = { Icon(Icons.Filled.AttachFile, contentDescription = null) },
                    onClick = { onInsertMenuChange(false); onInsertFile() },
                )
                DropdownMenuItem(
                    text = { Text("Table…") },
                    onClick = { onInsertMenuChange(false); onInsertTableRequest() },
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
