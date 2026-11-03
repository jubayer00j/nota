package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nota.data.attachments.AttachmentStore
import app.nota.data.attachments.PendingInsertStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.html.FileSegment
import app.nota.data.html.ImageSegment
import app.nota.data.html.NoteDocument
import app.nota.data.html.TableSegment
import app.nota.data.html.TextSegment
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteLockRepository
import app.nota.data.repository.NoteRepository
import app.nota.data.security.NoteLockBiometricStore
import app.nota.data.security.NoteLockDeviceKeyProvider
import app.nota.data.security.BiometricStep
import app.nota.data.security.StepResult
import app.nota.data.templates.TemplateRepository
import app.nota.ui.theme.NotaSpacing
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Block-drag UI regression tests — Phase 3.6 (G1) origin, Phase
 * 3.8 model. Drives the REAL [RichNoteEditorScreen] (the
 * NotesSwipeGhostUiTest pattern): a long press ON THE BLOCK'S
 * BODY — file, table and image — a drag past a neighbouring
 * BLOCK, a release, and the PERSISTED document order is asserted
 * from Room.
 *
 * Phase 3.8 is the drag-ARCHITECTURE rebuild (Jubayer's 0.4.3
 * device verdict: activation and short swaps worked, but the
 * dragged block TREMBLED because the document reordered and
 * recomposed under the finger on every mid-drag swap). The
 * model under test is now a drag SESSION: a long press claims
 * the block, the document freezes, the block's content rides a
 * translated overlay (the ghost), an insertion target is
 * computed live from the ghost's edges against the list's
 * laid-out geometry, and exactly ONE reorder + persist happens
 * when the finger lifts. The tests assert the session model's
 * defining properties:
 *
 * 1. STARTS LAND ON BODY CENTRES. In 3.6 the touches started on
 *    convenient label nodes. Now every drag starts at the dragged
 *    block's measured body centre — the file card's middle, the
 *    image's middle — the grab a hand actually makes. (The table
 *    is the one exception in the VERTICAL: its exact centre is
 *    a cell text field, whose long press wakes the platform text
 *    magnifier and crashes under Robolectric on a null Surface —
 *    a harness artifact, not product behaviour. The table's
 *    start is its body centre horizontally, on its footer row
 *    vertically: still the block's own card, never the rail.)
 * 2. TRAVEL IS EDGE TRAVEL. The insertion target counts a
 *    neighbour as PASSED when the ghost's leading edge pushes
 *    BLOCK_SWAP_OVERSHOOT (24 dp) past the neighbour's near
 *    edge — the thresholds 3.7 shipped, kept verbatim; only
 *    what happens AT the threshold changed (target bookkeeping
 *    instead of a live swap). Each test aims just past that
 *    threshold (overshoot + a bounded 40 px margin absorbing
 *    the few px by which inner label nodes misreport the layout
 *    item), FAR short of what centre-crossing would need — so
 *    these tests fail on the 3.6 math and pass on the session
 *    math.
 * 3. NOTHING PERSISTS WHILE THE FINGER IS DOWN. Each test
 *    reads Room BETWEEN the move and the lift — from inside the
 *    single injection block, with the finger still down — and
 *    asserts the persisted order is still the ORIGINAL. On the
 *    3.6/3.7 swap model a swap persisted synchronously from the
 *    gesture callback, so this assertion has teeth exactly
 *    where it matters. On the session model it cannot fail:
 *    the segment list is never touched until the lift commits
 *    it. (An earlier 3.8 draft drove press/move/lift as THREE
 *    separate performTouchInput calls to make this observation
 *    from outside the block; the lift never arrived — pointer
 *    state does not survive across injection calls in this
 *    harness, the session hung, and all three tests timed out.
 *    Hence: one block, observation inside it.)
 *
 * Why the drag is still ONE move in a TALL window (the Phase 3.6
 * verification arc — harness failure modes, none in the
 * product):
 *
 * 1. STEPPED GLIDES STARVE. The editor's auto-scroll loop ticks
 *    every 16 ms while a session is live. In the default 470 dp
 *    window these short notes barely exceed the list viewport,
 *    so the ghost sits in an edge zone almost immediately; under
 *    Robolectric's paused looper the ticks are processed in
 *    bursts between injected moves, the list walks under the
 *    finger, the pointer coordinates remap, and the delivered
 *    travel stalls.
 * 2. THE IMAGE'S SEMANTICS LIE. The image block's
 *    text/description nodes overstate its layout extent, so its
 *    geometry comes from the drag rails instead: rail tops
 *    differ from item tops by one constant padding, which
 *    cancels in every difference taken.
 *
 * (The swap-era third failure mode — the overshoot DOUBLE-SWAP,
 * where one glide's tick re-evaluated the swap past the next
 * segment too — is RETIRED: there is no swap evaluator anymore.
 * The target is one pure function of the ghost's position,
 * evaluated per move, and the commit fires once, on the lift.)
 *
 * A physical finger produces a continuous real-time stream and
 * exhibits none of this. Assertions throughout: the exact
 * persisted order, from Room — before, during and after.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorBlockDragUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Raw-key device provider, the NoteLockUnlockState test pattern. */
    private class RawKeyProvider : NoteLockDeviceKeyProvider {
        private val key = ByteArray(32) { (0x40 + it).toByte() }

        override fun encrypt(plain: ByteArray): String {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
        }

        override fun decrypt(packedBase64: String): ByteArray? = try {
            val packed = Base64.getDecoder().decode(packedBase64)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, packed.copyOfRange(0, 12)),
            )
            cipher.doFinal(packed.copyOfRange(12, packed.size))
        } catch (e: Exception) {
            null
        }

        override fun sealCipherResult(): StepResult<Cipher> =
            StepResult.Ok(
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
                },
            )

        override fun openCipherResult(packedBase64: String): StepResult<Cipher> = try {
            val packed = Base64.getDecoder().decode(packedBase64)
            StepResult.Ok(
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        SecretKeySpec(key, "AES"),
                        GCMParameterSpec(128, packed.copyOfRange(0, 12)),
                    )
                },
            )
        } catch (e: Exception) {
            StepResult.Failed(BiometricStep.CIPHER_INIT, "fake provider")
        }
    }

    private lateinit var provider: DatabaseProvider
    private lateinit var repository: NoteRepository
    private lateinit var lockRepository: NoteLockRepository
    private lateinit var insertStore: PendingInsertStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = DatabaseProvider.forDatabase(context, db)
        repository = NoteRepository(provider)
        val attachmentStore = AttachmentStore(context, provider)
        val biometricStore = NoteLockBiometricStore(
            PreferencesRepository(context), RawKeyProvider(), provider,
        )
        lockRepository = NoteLockRepository(repository, provider, attachmentStore, biometricStore)
        insertStore = PendingInsertStore(tempFolder.newFolder("files"))
    }

    private fun composeEditor(noteId: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = RichNoteEditorViewModel(
            repository,
            AttachmentStore(context, provider),
            TemplateRepository(context),
            lockRepository,
        )
        composeRule.setContent {
            app.nota.ui.theme.NotaTheme {
                RichNoteEditorScreen(
                    noteId = noteId,
                    onBack = {},
                    viewModel = vm,
                    pendingInsertStore = insertStore,
                    databaseProvider = provider,
                )
            }
        }
        // The screen opens in READ mode (Phase 3.6, G3): enter edit
        // mode through the pencil, like a user would.
        awaitTrue("read mode composed") {
            try {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
        composeRule.onNodeWithContentDescription("Edit note").performClick()
        awaitTrue("edit mode composed") {
            try {
                composeRule.onNodeWithContentDescription("Done editing").assertIsDisplayed()
                true
            } catch (e: AssertionError) {
                false
            }
        }
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $description\nSemantics: ${dumpSemantics()}")
    }

    /** Every text + content description currently in the tree. */
    private fun dumpSemantics(): String {
        val out = mutableListOf<String>()
        fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)
                ?.forEach { out += "text:" + it.text.take(40) }
            node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.forEach { out += "desc:" + it }
            node.children.forEach(::walk)
        }
        return try {
            walk(composeRule.onRoot().fetchSemanticsNode())
            out.joinToString(" | ").take(900)
        } catch (e: Throwable) {
            "dump failed: $e"
        }
    }

    private fun boundsOf(text: String) =
        composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    /** The drag rails' top edges, in segment order — each rail icon
     *  sits one [NotaSpacing.sm] padding below its item's top, a
     *  constant offset that cancels in every difference (class
     *  doc, 3). */
    private fun railTops(): List<Float> =
        composeRule.onAllNodesWithContentDescription("Drag to move block")
            .fetchSemanticsNodes().map { it.boundsInRoot.top }

    /** The passed-neighbour overshoot (the product's
     *  BLOCK_SWAP_OVERSHOOT) in harness pixels. */
    private fun overshootPx(): Float = with(composeRule.density) { 24.dp.toPx() }

    /** One inter-item spacing step in harness pixels (the product's
     *  drag geometry spaces items by [NotaSpacing.sm]). */
    private fun spacingPx(): Float = with(composeRule.density) { NotaSpacing.sm.toPx() }

    /** The horizontal centre of the editor surface — every block
     *  card spans it, so a start here lands on the block's body,
     *  never on the 24 dp rail at its left edge. */
    private fun bodyCentreX(): Float =
        composeRule.onRoot().fetchSemanticsNode().boundsInRoot.center.x

    /**
     * Long-press-drag starting at the root-space point [start],
     * as ONE injection block: press and hold past the long-press
     * timeout (the first small move carries the event clock past
     * it, inside the surface watch's drift allowance), travel the
     * whole [totalDy] in ONE move — see the class doc for why a
     * stepped glide is unreliable in this harness; each test here
     * crosses exactly one neighbouring block, and the target
     * evaluates once per move, so one move is the exact dose —
     * then run [midDragCheck] with the finger STILL DOWN (class
     * doc, 3), and lift.
     */
    private fun longPressDragAt(
        start: Offset,
        totalDy: Float,
        midDragCheck: () -> Unit = {},
    ) {
        val rootTopLeft = composeRule.onRoot().fetchSemanticsNode()
            .boundsInRoot.topLeft
        val local = start - rootTopLeft
        composeRule.onRoot().performTouchInput {
            down(local)
            advanceEventTime(700)
            moveBy(Offset(0f, 2f))
            advanceEventTime(16)
            moveBy(Offset(0f, totalDy - 2f))
            advanceEventTime(16)
            midDragCheck()
            up()
        }
    }

    private fun persistedKinds(noteId: String): List<String> = runBlocking {
        val html = repository.getById(noteId)?.contentHtml ?: ""
        NoteDocument.parse(html).map { segment ->
            when (segment) {
                is TextSegment -> "text:" + NoteDocument.plainText(listOf(segment)).take(12)
                is FileSegment -> "file:" + segment.fileName
                is TableSegment -> "table"
                is ImageSegment -> "image:" + segment.alt
                else -> segment.javaClass.simpleName
            }
        }
    }

    private fun awaitOrder(noteId: String, expected: List<String>) {
        awaitTrue("persisted order $expected") {
            persistedKinds(noteId) == expected
        }
        assertEquals(expected, persistedKinds(noteId))
    }

    /** The current text of every editable field on screen (title,
     *  rich text, table cells, task items), in semantics order. */
    private fun fieldTexts(): List<String> =
        composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText)?.text }

    /** Replaces the text of the one editable field currently
     *  showing [current] — the semantics SetText action, which the
     *  field reports through its normal onValueChange (so the
     *  segment write-through runs exactly as for typed input). */
    private fun setFieldText(current: String, replacement: String) {
        val nodes = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        val index = nodes.indexOfFirst {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == current
        }
        if (index < 0) {
            fail("no editable field showing '$current'; fields=${fieldTexts()}")
        }
        composeRule.onAllNodes(hasSetTextAction())[index]
            .performTextReplacement(replacement)
    }

    @Test
    fun dragFileBlock_downPastTable_reordersAndPersists() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Drag File",
                contentHtml = "<div data-nota-file=\"att-drag\" data-name=\"drag-report.pdf\" " +
                    "data-mime=\"application/pdf\"></div>" +
                    "<table><tr><td>CellA</td><td>CellB</td></tr>" +
                    "<tr><td>CellC</td><td>CellD</td></tr></table>" +
                    "<p>Charlie closing paragraph</p>",
            )
        }
        composeEditor(note.id)
        // The grab lands on the file card's BODY CENTRE: the card
        // runs name → mime → error line, so its centre is the
        // midpoint of those extremes, at the surface's centre x.
        val fileTop = boundsOf("drag-report.pdf").top
        val fileBottom = boundsOf("Attachment not found on this device").bottom
        val tableTop = boundsOf("CellA").top
        val before = persistedKinds(note.id)
        // Edge travel: the ghost's bottom edge starts one item gap
        // above the table's top; it must end the overshoot PAST
        // that top (+ the bounded margin). The label-measured gap
        // overstates the true one by the labels' chrome offsets,
        // which only adds safety margin — and the total stays far
        // short of both the closing paragraph's threshold and the
        // old centre-to-centre distance.
        longPressDragAt(
            Offset(bodyCentreX(), (fileTop + fileBottom) / 2f),
            (tableTop - fileBottom) + overshootPx() + 40f,
            midDragCheck = {
                // The session's defining property: the finger is
                // still DOWN, the target already computed — and
                // the persisted document is still the original
                // order (class doc, 3).
                assertEquals(before, persistedKinds(note.id))
            },
        )
        awaitOrder(
            note.id,
            listOf(
                "table",
                "file:drag-report.pdf",
                "text:Charlie clos",
            ),
        )
    }

    @Test
    fun dragTableBlock_upPastFile_reordersAndPersists() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Drag Table",
                contentHtml = "<div data-nota-file=\"att-drag\" data-name=\"drag-report.pdf\" " +
                    "data-mime=\"application/pdf\"></div>" +
                    "<table><tr><td>CellA</td><td>CellB</td></tr>" +
                    "<tr><td>CellC</td><td>CellD</td></tr></table>" +
                    "<p>Charlie closing paragraph</p>",
            )
        }
        composeEditor(note.id)
        val fileBottom = boundsOf("Attachment not found on this device").bottom
        val tableTop = boundsOf("CellA").top
        val footerCentreY = boundsOf("+ Row").center.y
        val before = persistedKinds(note.id)
        // The grab lands on the table's body — centre x, footer-row
        // y (class doc: the true centre is a cell text field, whose
        // long press wakes the platform magnifier and crashes this
        // harness). Edge travel, sign-flipped: the ghost's top edge
        // must end the overshoot ABOVE the file's bottom edge.
        longPressDragAt(
            Offset(bodyCentreX(), footerCentreY),
            fileBottom - overshootPx() - 40f - tableTop,
            midDragCheck = {
                // Finger still down: the persisted order is
                // untouched.
                assertEquals(before, persistedKinds(note.id))
            },
        )
        awaitOrder(
            note.id,
            listOf(
                "table",
                "file:drag-report.pdf",
                "text:Charlie clos",
            ),
        )
    }

    @Test
    fun dragTableBlock_cellTextSurvivesGhostRemount() {
        // Phase 3.8 regression (Codex finding 3, PR #21): a table
        // cell's text must survive the drag's slot -> ghost -> slot
        // remount EXACTLY — boundary spaces included. Before the
        // fix the field's text state lived in a remember() inside
        // the block editor: the remount discarded it and the field
        // re-seeded from the stored HTML through the TRIMMING
        // projection, so the typed " A " came back as "A" and the
        // next edit would have written the trimmed text into the
        // document — silent data loss. The state is now hoisted
        // above the block editors and the seed projection is
        // lossless; both halves are exercised here.
        val note = runBlocking {
            repository.createRichNote(
                title = "Drag Cell Text",
                contentHtml = "<div data-nota-file=\"att-drag\" data-name=\"drag-report.pdf\" " +
                    "data-mime=\"application/pdf\"></div>" +
                    "<table><tr><td>CellA</td><td>CellB</td></tr>" +
                    "<tr><td>CellC</td><td>CellD</td></tr></table>" +
                    "<p>Charlie closing paragraph</p>",
            )
        }
        composeEditor(note.id)
        // Type boundary spaces into the first cell: the field and
        // (through the write-through) the stored segment now both
        // hold " A ".
        setFieldText("CellA", " A ")
        awaitTrue("cell field holds the typed text") {
            fieldTexts().contains(" A ")
        }
        // Drag the table up past the file — the same edge travel
        // as the table drag test, with the table's item top taken
        // from its rail (the CellA label is a field now, so its
        // bounds no longer name the cell's text).
        val fileBottom = boundsOf("Attachment not found on this device").bottom
        val tableItemTop = railTops()[1] - spacingPx()
        val footerCentreY = boundsOf("+ Row").center.y
        longPressDragAt(
            Offset(bodyCentreX(), footerCentreY),
            fileBottom - overshootPx() - 40f - tableItemTop,
        )
        awaitOrder(
            note.id,
            listOf(
                "table",
                "file:drag-report.pdf",
                "text:Charlie clos",
            ),
        )
        // The drop's persist carries the cell verbatim...
        val html = runBlocking { repository.getById(note.id)?.contentHtml ?: "" }
        assertTrue(
            "persisted cell keeps its boundary spaces: $html",
            html.contains("<td> A </td>"),
        )
        // ...and the cell field, recomposed in its new slot after
        // the ghost remount, still shows the exact typed text.
        awaitTrue("cell field still holds ' A ' after the drag") {
            fieldTexts().contains(" A ")
        }
    }

    @Test
    fun dragImageBlock_downPastFile_reordersAndPersists() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Drag Image",
                contentHtml = "<p><img src=\"nota-attachment://img-drag\" alt=\"Drag image\"></p>" +
                    "<div data-nota-file=\"att-drag\" data-name=\"drag-report.pdf\" " +
                    "data-mime=\"application/pdf\"></div>" +
                    "<p>Charlie closing paragraph</p>",
            )
        }
        composeEditor(note.id)
        // The image's own semantics nodes overstate its layout
        // extent (class doc, 3), so its geometry comes from the
        // drag rails: consecutive rail tops differ by the item's
        // laid-out height plus one inter-item spacing, and each
        // rail top sits one spacing below its item top.
        val rails = railTops()
        val imageItemTop = rails[0] - spacingPx()
        val imageItemSize = (rails[1] - rails[0]) - spacingPx()
        // The grab lands on the image's BODY CENTRE — on the image
        // itself, not its header label. Edge travel: the ghost's
        // bottom starts one spacing above the file's top and must
        // end the overshoot past it (+ the bounded margin).
        val before = persistedKinds(note.id)
        longPressDragAt(
            Offset(bodyCentreX(), imageItemTop + imageItemSize / 2f),
            spacingPx() + overshootPx() + 40f,
            midDragCheck = {
                // Finger still down: the persisted order is
                // untouched.
                assertEquals(before, persistedKinds(note.id))
            },
        )
        awaitOrder(
            note.id,
            listOf(
                "file:drag-report.pdf",
                "image:Drag image",
                "text:Charlie clos",
            ),
        )
    }
}
