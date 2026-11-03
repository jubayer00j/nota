package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import kotlin.math.abs
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
 * The display is deliberately TALL (h1000dp): in the default
 * 470dp window these short notes barely exceed the list viewport,
 * so a dragged block enters the G1 auto-scroll edge zone almost
 * immediately; under Robolectric's virtual clock the 16 ms
 * auto-scroll loop then interleaves with the synthetic event
 * stream, the list scrolls mid-gesture, the pointer coordinates
 * remap, and the stream dies before the centre can cross (probe-
 * verified: the drag accumulated ~100 of 186 px, then a −6 px
 * remapped event, then silence). A physical finger cannot be
 * remapped that way — this is a harness interaction, not the
 * reorder logic under test. With the tall window the content
 * cannot scroll at all, the drag runs on finger travel alone,
 * and the persisted order is asserted exactly as before.
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

    private fun boundsOfDescription(description: String) =
        composeRule.onNodeWithContentDescription(description)
            .fetchSemanticsNode().boundsInRoot

    /**
     * A block's vertical centre, estimated from its topmost and
     * bottommost findable nodes. The swap math is centre-to-centre,
     * so measuring between two blocks' LABELS (both near their
     * blocks' tops) under-travels by half the height difference —
     * the first version of these tests did exactly that and the
     * dragged centre stopped short of the neighbour's centre.
     *
     * Even centre-to-centre is only the EDGE of the swap window:
     * inner text nodes are not the layout item (card padding and
     * field chrome distort the estimate by tens of pixels), so the
     * tests aim for the MIDDLE of the window — centre-to-centre
     * plus half the TARGET block's measured height. The window's
     * far edge is the next segment's centre, always more than half
     * the target's height beyond the near edge (plus spacing), so
     * the aim cannot overshoot into a second swap. Deriving the
     * margin from the measured bounds keeps it right at any
     * density.
     */
    private fun blockCentreY(top: Float, bottom: Float) = (top + bottom) / 2f

    /**
     * Long-press-drag on the node showing [text]: the finger holds
     * past the long-press timeout (the first small move carries the
     * event clock past it, inside touch slop), then travels [totalDy]
     * in steps so centre-crossing swaps engage progressively.
     */
    private fun longPressDrag(text: String, totalDy: Float) {
        composeRule.onNodeWithText(text).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, 2f))
            advanceEventTime(16)
            var travelled = 2f
            val step = if (totalDy >= 0) 24f else -24f
            while (abs(totalDy - travelled) > abs(step)) {
                moveBy(Offset(0f, step))
                travelled += step
                advanceEventTime(16)
            }
            moveBy(Offset(0f, totalDy - travelled))
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
        // Aim at the middle of the swap window (see blockCentreY).
        longPressDrag(
            "drag-report.pdf",
            tableCentre - fileCentre + (tableBottom - tableTop) / 2f,
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
        // block itself either way. Travel aims at the middle of
        // the swap window (see blockCentreY).
        longPressDrag(
            "+ Row",
            fileCentre - tableCentre - (tableBottom - tableTop) / 2f,
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
        // The image block runs header label → image area (the
        // content-description node); the file card as in the other
        // tests. The long press lands on the header label — the
        // block itself.
        val imageTop = boundsOf("Drag image").top
        val imageBottom = boundsOfDescription("Drag image").bottom
        val fileTop = boundsOf("drag-report.pdf").top
        val fileBottom = boundsOf("Attachment not found on this device").bottom
        val imageCentre = blockCentreY(imageTop, imageBottom)
        val fileCentre = blockCentreY(fileTop, fileBottom)
        // Aim at the middle of the swap window (see blockCentreY).
        longPressDrag(
            "Drag image",
            fileCentre - imageCentre + (fileBottom - fileTop) / 2f,
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
