package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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

/** THROWAWAY diagnostic for the table-drag timeouts — not for merge. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DragDiagnosticTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

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
                Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, packed.copyOfRange(0, 12)),
            )
            cipher.doFinal(packed.copyOfRange(12, packed.size))
        } catch (e: Exception) { null }
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
                        Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
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

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            composeRule.waitForIdle()
            if (condition()) return
            Thread.sleep(50)
        }
        fail("Timed out: $description")
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
        awaitTrue("read mode") {
            try {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
                true
            } catch (e: AssertionError) { false }
        }
        composeRule.onNodeWithContentDescription("Edit note").performClick()
        awaitTrue("edit mode") {
            try {
                composeRule.onNodeWithContentDescription("Done editing").assertIsDisplayed()
                true
            } catch (e: AssertionError) { false }
        }
    }

    private fun boundsOf(text: String) =
        composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

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

    private fun geometry(tag: String): String {
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val rails = composeRule.onAllNodesWithContentDescription("Drag to move block")
            .fetchSemanticsNodes().map { it.boundsInRoot }
        val fileName = boundsOf("drag-report.pdf")
        val fileErr = boundsOf("Attachment not found on this device")
        val cellA = boundsOf("CellA")
        val plusRow = boundsOf("+ Row")
        return "$tag root=[${root.top}..${root.bottom}] h=${root.bottom - root.top} " +
            "rails=${rails.map { "[${it.top}..${it.bottom}]" }} " +
            "fileName=[${fileName.top}..${fileName.bottom}] " +
            "fileErr=[${fileErr.top}..${fileErr.bottom}] " +
            "cellA=[${cellA.top}..${cellA.bottom}] " +
            "plusRow=[${plusRow.top}..${plusRow.bottom}]"
    }

    @Test
    fun diagnoseFileDownDrag() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Diag",
                contentHtml = "<div data-nota-file=\"att-drag\" data-name=\"drag-report.pdf\" " +
                    "data-mime=\"application/pdf\"></div>" +
                    "<table><tr><td>CellA</td><td>CellB</td></tr>" +
                    "<tr><td>CellC</td><td>CellD</td></tr></table>" +
                    "<p>Charlie closing paragraph</p>",
            )
        }
        composeEditor(note.id)
        val before = geometry("BEFORE")
        val fileCentre = (boundsOf("drag-report.pdf").top +
            boundsOf("Attachment not found on this device").bottom) / 2f
        val tableTop = boundsOf("CellA").top
        val tableBottom = boundsOf("+ Row").bottom
        val tableCentre = (tableTop + tableBottom) / 2f
        val totalDy = tableCentre - fileCentre + (tableBottom - tableTop) / 2f
        // Same gesture as EditorBlockDragUiTest.longPressDrag.
        composeRule.onNodeWithText("drag-report.pdf").performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, 2f))
            advanceEventTime(16)
            var travelled = 2f
            val step = 24f
            while (abs(totalDy - travelled) > abs(step)) {
                moveBy(Offset(0f, step))
                travelled += step
                advanceEventTime(16)
            }
            moveBy(Offset(0f, totalDy - travelled))
            advanceEventTime(16)
            up()
        }
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        Thread.sleep(500)
        ShadowLooper.idleMainLooper()
        composeRule.waitForIdle()
        val after = geometry("AFTER")
        fail(
            "DIAG totalDy=$totalDy fileCentre=$fileCentre tableCentre=$tableCentre\n" +
                "$before\n$after\npersisted=${persistedKinds(note.id)}",
        )
    }
}
