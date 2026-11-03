package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Phase 3.6 (G1) — UI-level regression tests for the rebuilt block
 * drag. Drives the REAL [RichNoteEditorScreen] (the
 * NotesSwipeGhostUiTest pattern): a long press ON THE BLOCK ITSELF
 * — file, table and image — then a drag past a neighbouring BLOCK,
 * and the PERSISTED document order is asserted from Room. The 3.5
 * rail-only drag is not what is exercised here: the touch starts on
 * the block's own content.
 *
 * Travel distances are measured between block nodes on purpose:
 * the rich text editor does not publish its content in the
 * semantics tree, so paragraph nodes cannot be located — block
 * neighbours (a file name, a table cell, an image label) can.
 *
 * Why the drag is driven exactly this way (probe-verified across
 * the Phase 3.6 verification arc — three failure modes, all in
 * the harness, none in the product):
 *
 * 1. STEPPED GLIDES STARVE. The editor's G1 auto-scroll loop
 *    ticks every 16 ms while a drag is live. In the default
 *    470 dp window these short notes barely exceed the list
 *    viewport, so the dragged block sits in an edge zone almost
 *    immediately; under Robolectric's paused looper the ticks are
 *    processed in bursts between the injected moves, the list
 *    walks under the finger, the pointer coordinates remap, and
 *    the dragged centre stalls short of the crossing (recorded:
 *    ~100 of 186 px delivered, then a −6 px phantom delta).
 * 2. OVERSHOOT DOUBLE-SWAPS. The same tick re-evaluates the swap
 *    after the intended one: a finger parked past the NEXT
 *    segment's centre swaps a second time on the tick (recorded:
 *    an image dragged past its file neighbour landed past the
 *    closing paragraph too). Travel is therefore aimed just past
 *    the crossing — a bounded margin over the exact threshold,
 *    far short of the next centre.
 * 3. THE IMAGE'S SEMANTICS LIE. The image block's text/description
 *    nodes overstate its layout extent, so its travel is computed
 *    from the drag rails instead: rail tops are exact layout
 *    quantities (item tops), and the dragged block's size is the
 *    rail-to-rail distance minus the item spacing.
 *
 * Defences, combined: a TALL display (h1000dp) in which these
 * notes cannot scroll at all, so no auto-scroll tick can ever
 * walk the list; a SINGLE move, so the swap is evaluated inside
 * one event pass on the full offset; and measured travel with
 * the bounded margins above. A physical finger produces a
 * continuous real-time stream and exhibits none of this — the
 * reorder logic, swap math and persist path were exonerated by
 * the probe traces (drag engaged, exact travel delivered, one
 * swap, persisted order correct) before these tests were shaped
 * this way. Assertions are unchanged throughout: the exact
 * persisted order is asserted from Room.
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

    /**
     * A block's vertical centre, estimated from its topmost and
     * bottommost findable nodes. The swap math is centre-to-centre,
     * so measuring between two blocks' LABELS (both near their
     * blocks' tops) under-travels by half the height difference —
     * the first version of these tests did exactly that and the
     * dragged centre stopped short of the neighbour's centre.
     * Travel is the centre-to-centre distance plus a bounded
     * margin (40 px): enough to absorb the few px by which inner
     * text nodes misreport the layout item (card padding, field
     * chrome), far short of the following segment's centre — see
     * the class doc, failure mode 2.
     */
    private fun blockCentreY(top: Float, bottom: Float) = (top + bottom) / 2f

    /** The drag rails' top edges, in segment order — exact layout
     *  item tops (each rail icon sits a padding step below its
     *  item's top), for the image test's travel (class doc, 3). */
    private fun railTops(): List<Float> =
        composeRule.onAllNodesWithContentDescription("Drag to move block")
            .fetchSemanticsNodes().map { it.boundsInRoot.top }

    /**
     * Long-press-drag on the node showing [text]: the finger holds
     * past the long-press timeout (the first small move carries the
     * event clock past it, inside touch slop), then travels the
     * whole [totalDy] in ONE move — see the class doc for why a
     * stepped glide is unreliable in this harness. Each test here
     * crosses exactly one neighbouring block, and the swap check
     * evaluates once per move, so one move is the exact dose.
     */
    private fun longPressDrag(text: String, totalDy: Float) {
        composeRule.onNodeWithText(text).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, 2f))
            advanceEventTime(16)
            moveBy(Offset(0f, totalDy - 2f))
            advanceEventTime(16)
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
        // Block centres from each block's extreme nodes: the file
        // card runs name → mime → error line; the table runs its
        // first cell → its footer row.
        val fileTop = boundsOf("drag-report.pdf").top
        val fileBottom = boundsOf("Attachment not found on this device").bottom
        val tableTop = boundsOf("CellA").top
        val tableBottom = boundsOf("+ Row").bottom
        val fileCentre = blockCentreY(fileTop, fileBottom)
        val tableCentre = blockCentreY(tableTop, tableBottom)
        // Bounded margin past the crossing (see blockCentreY).
        longPressDrag(
            "drag-report.pdf",
            tableCentre - fileCentre + 40f,
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
        val fileTop = boundsOf("drag-report.pdf").top
        val fileBottom = boundsOf("Attachment not found on this device").bottom
        val tableTop = boundsOf("CellA").top
        val tableBottom = boundsOf("+ Row").bottom
        val fileCentre = blockCentreY(fileTop, fileBottom)
        val tableCentre = blockCentreY(tableTop, tableBottom)
        // The drag starts on the table's FOOTER text, not a cell:
        // a long press inside a text-field cell wakes the platform
        // text magnifier, which crashes under Robolectric (null
        // Surface) before the block drag can complete. The footer
        // is part of the same block — the long press lands on the
        // block itself either way. Travel is the crossing plus the
        // same bounded margin, sign-flipped (see blockCentreY).
        longPressDrag(
            "+ Row",
            fileCentre - tableCentre - 40f,
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
        // extent (class doc, failure mode 3), so its travel is
        // computed from the drag rails instead: the rail-to-rail
        // distance between the image and the file IS the image
        // block's laid-out height plus the item spacing, and the
        // exact crossing is that distance plus half the size
        // difference. A bounded 30 px margin past the crossing
        // keeps the landing inside the file's swap window. The
        // long press lands on the header label — the block itself.
        val rails = railTops()
        val deltaTops = rails[1] - rails[0]
        val imageSize = deltaTops - 8f
        val fileTop = boundsOf("drag-report.pdf").top
        val fileBottom = boundsOf("Attachment not found on this device").bottom
        val fileSize = (fileBottom - fileTop) + 16f
        longPressDrag(
            "Drag image",
            deltaTops + (fileSize - imageSize) / 2f + 30f,
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
