package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nota.data.attachments.AttachmentStore
import app.nota.data.attachments.PendingInsertStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteLockRepository
import app.nota.data.repository.NoteRepository
import app.nota.data.security.BiometricStep
import app.nota.data.security.NoteLockBiometricStore
import app.nota.data.security.NoteLockDeviceKeyProvider
import app.nota.data.security.StepResult
import app.nota.data.templates.TemplateRepository
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
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
 * Phase 6 (Editor Refinement #3) — UI-level. Drives the REAL
 * [RichNoteEditorScreen] over real (in-memory) Room, the
 * RichCellsNestedUiTest pattern.
 *
 * Part A (autolink): a delimiter typed after a URL token in a
 * text segment AND in a rich table cell linkifies it through
 * the library's own link span — the stored bytes gain the
 * anchor the link dialog would have written. (Seeding never
 * triggers: the stored text is complete before the editor
 * opens, so only the edit in the test can linkify.)
 *
 * Part B ([[ popup): typing an unclosed [[ query raises the
 * caret-anchored suggestion popup — in a text segment and,
 * new in this chapter, inside a rich table cell — and picking
 * the row inserts the same [[Title]] literal text the old chip
 * strip inserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorAutolinkPopupUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Raw-key device provider, the EditorReadModeUiTest pattern. */
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

    private fun composeEditor(noteId: String, startInEdit: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = RichNoteEditorViewModel(
            repository,
            AttachmentStore(context, provider),
            TemplateRepository(context),
            lockRepository,
            app.nota.data.reminders.ReminderRepository(provider),
            app.nota.data.reminders.ReminderScheduler(
                app.nota.data.reminders.AndroidReminderAlarmGateway(context),
                provider,
            ),
        )
        composeRule.setContent {
            app.nota.ui.theme.NotaTheme {
                RichNoteEditorScreen(
                    noteId = noteId,
                    startInEdit = startInEdit,
                    onBack = {},
                    viewModel = vm,
                    pendingInsertStore = insertStore,
                    databaseProvider = provider,
                )
            }
        }
    }

    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
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

    private fun exists(description: String, finder: () -> Unit): Boolean = try {
        finder()
        true
    } catch (e: AssertionError) {
        false
    }

    private fun storedHtml(noteId: String): String =
        runBlocking { repository.getById(noteId)?.contentHtml ?: "" }

    private fun awaitEditMode() {
        awaitTrue("edit mode") {
            exists("done") {
                composeRule.onNodeWithContentDescription("Done editing").assertIsDisplayed()
            }
        }
    }

    private fun finishEditing() {
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("back to read mode") {
            exists("edit door") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
    }

    /** Replaces the text of the editable field currently showing
     *  [current] — the RichCellsNestedUiTest pattern. */
    private fun replaceFieldText(current: String, replacement: String) {
        val nodes = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        val index = nodes.indexOfFirst {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == current
        }
        if (index < 0) fail("no editable field showing '$current'")
        composeRule.onAllNodes(hasSetTextAction())[index]
            .performTextReplacement(replacement)
    }

    // ------------------------------------------------------ Part A

    @Test
    fun autolink_strayBracketsOnEarlierLine_doNotBlockLaterLines() {
        // Codex P2 (PR #42): an unmatched "[[" in an earlier
        // paragraph must not disable autolinking for the rest of
        // the field — the real [[ trigger cannot cross a newline,
        // and the autolink guard now defers to it exactly.
        val note = runBlocking {
            repository.createRichNote(
                title = "Stray brackets",
                contentHtml = "<p>[[abc</p><p>example.com</p>",
            )
        }
        composeEditor(note.id, startInEdit = true)
        awaitEditMode()

        // Append ONE space to whichever field holds the URL —
        // how paragraphs are separated inside a field's text is
        // the library's own business, so match on content rather
        // than an exact field text.
        val nodes = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        val index = nodes.indexOfFirst {
            it.config.getOrNull(SemanticsProperties.EditableText)
                ?.text?.contains("example.com") == true
        }
        if (index < 0) fail("no editable field containing 'example.com'")
        val fieldText = nodes[index].config
            .getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
        composeRule.onAllNodes(hasSetTextAction())[index]
            .performTextReplacement(fieldText + " ")

        finishEditing()
        val html = storedHtml(note.id)
        assertTrue(
            "the URL on the later line still linkified: $html",
            html.contains("href=\"https://example.com\""),
        )
    }

    @Test
    fun autolink_typedSpaceAfterUrl_inTextSegment_linkifies() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Links",
                contentHtml = "<p>see example.com</p>",
            )
        }
        composeEditor(note.id, startInEdit = true)
        awaitEditMode()

        // The only change is ONE typed space after the token —
        // the delimiter trigger.
        replaceFieldText("see example.com", "see example.com ")

        finishEditing()
        val html = storedHtml(note.id)
        assertTrue(
            "the token linkified with the https:// href: $html",
            html.contains("href=\"https://example.com\""),
        )
        assertTrue(
            "the visible text stays the typed token: $html",
            html.contains(">example.com</a>"),
        )
    }

    @Test
    fun autolink_typedSpaceAfterUrl_inTableCell_linkifies() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Cell links",
                contentHtml = "<table><tbody><tr>" +
                    "<td><p>see example.com</p></td>" +
                    "</tr></tbody></table>",
            )
        }
        composeEditor(note.id, startInEdit = true)
        awaitEditMode()

        replaceFieldText("see example.com", "see example.com ")

        finishEditing()
        val html = storedHtml(note.id)
        assertTrue(
            "the cell token linkified too: $html",
            html.contains("href=\"https://example.com\""),
        )
    }

    // ------------------------------------------------------ Part B

    @Test
    fun linkPopup_inTextSegment_pickInsertsBracketTitle() {
        runBlocking {
            repository.createRichNote(
                title = "Target Note",
                contentHtml = "<p>target body</p>",
            )
        }
        val note = runBlocking {
            repository.createRichNote(
                title = "Draft",
                contentHtml = "<p>draft</p>",
            )
        }
        composeEditor(note.id, startInEdit = true)
        awaitEditMode()

        // Focus the field first (the popup derives from the
        // EDITED state), then type the trigger.
        composeRule.onNodeWithText("draft").performClick()
        replaceFieldText("draft", "[[Tar")

        awaitTrue("suggestion popup shows the target") {
            exists("popup row") {
                composeRule.onNodeWithText("Target Note").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Target Note").performClick()

        finishEditing()
        val html = storedHtml(note.id)
        assertTrue(
            "picking inserted the [[Title]] literal text: $html",
            html.contains("[[Target Note]]"),
        )
    }

    @Test
    fun linkPopup_inTableCell_pickInsertsBracketTitle() {
        runBlocking {
            repository.createRichNote(
                title = "Target Note",
                contentHtml = "<p>target body</p>",
            )
        }
        val note = runBlocking {
            repository.createRichNote(
                title = "Cell draft",
                contentHtml = "<table><tbody><tr>" +
                    "<td><p>Cell</p></td>" +
                    "</tr></tbody></table>",
            )
        }
        composeEditor(note.id, startInEdit = true)
        awaitEditMode()

        // Focus the CELL (the derivation follows the focused
        // cell's state — the chip strip never reached cells),
        // then type the trigger inside it.
        composeRule.onNodeWithText("Cell").performClick()
        replaceFieldText("Cell", "[[Tar")

        awaitTrue("suggestion popup shows the target for the cell") {
            exists("popup row") {
                composeRule.onNodeWithText("Target Note").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Target Note").performClick()

        finishEditing()
        val html = storedHtml(note.id)
        assertTrue(
            "the cell gained the [[Title]] insertion: $html",
            html.contains("[[Target Note]]"),
        )
    }
}
