package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.font.FontWeight
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
 * Phase 6 (rich table cells + nested checklists) — UI-level.
 * Drives the REAL [RichNoteEditorScreen] over real (in-memory)
 * Room, the EditorReadModeUiTest pattern.
 *
 * Part A (rich table cells): a formatted cell must RENDER its
 * formatting in read mode; in edit mode the formatting toolbar
 * must route to the focused CELL (block-style row hidden) and
 * back to the text segment when the caret returns there; and a
 * cell the user never touched must persist byte-identically —
 * the rich library's HTML round-trip must not rewrite it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RichCellsNestedUiTest {

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

    private fun enterEditMode() {
        awaitTrue("edit door") {
            exists("edit") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithContentDescription("Edit note").performClick()
        awaitTrue("edit mode") {
            exists("done") {
                composeRule.onNodeWithContentDescription("Done editing").assertIsDisplayed()
            }
        }
    }

    // ------------------------------------------------------ Part A

    @Test
    fun readMode_tableCellRendersRich_boldSpanSurvives() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Rich cells",
                contentHtml = "<table><tbody><tr><th>Subject</th></tr>" +
                    "<tr><td><p><strong>Bold cell</strong></p></td></tr>" +
                    "<tr><td><p>Plain cell</p></td></tr></tbody></table>",
            )
        }
        composeEditor(note.id)

        awaitTrue("rich cell rendered in read mode") {
            exists("bold cell") {
                composeRule.onNodeWithText("Bold cell").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Plain cell").assertIsDisplayed()
        // The cell's text carries a REAL bold span — the read view
        // renders the cell's HTML rich, it does not flatten it to
        // plain text the way the old codec projection did.
        val texts = composeRule.onNodeWithText("Bold cell").fetchSemanticsNode()
            .config.getOrNull(SemanticsProperties.Text).orEmpty()
        assertTrue(
            "the read-mode cell must carry a bold span",
            texts.any { annotated ->
                annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold }
            },
        )
    }

    @Test
    fun editMode_cellFocus_hidesBlockStyles() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Routing",
                contentHtml = "<p>Body para</p>" +
                    "<table><tbody><tr><td><p>Cell one</p></td>" +
                    "<td><p>Cell two</p></td></tr></tbody></table>",
            )
        }
        composeEditor(note.id)
        enterEditMode()

        // Focus goes straight into a table cell — the first (and
        // only) rich editor focused in this test: the inline row
        // stays (Bold is offered, acting on the cell), the
        // block-style row hides.
        // Split from editMode_textFocus_showsBlockStyles: moving
        // focus BETWEEN two rich editors inside one Robolectric
        // test hits a deterministic NPE in Magnifier.dismiss()
        // (the platform magnifier's surface is null under NATIVE
        // graphics), so each routing direction gets its own
        // single-transition test.
        composeRule.onNodeWithText("Cell one").performClick()
        awaitTrue("block styles hidden with cell focus") {
            exists("no H1") { composeRule.onNodeWithText("H1").assertDoesNotExist() }
        }
        composeRule.onNodeWithText("B").assertExists()
    }

    @Test
    fun editMode_textFocus_showsBlockStyles() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Routing",
                contentHtml = "<p>Body para</p>" +
                    "<table><tbody><tr><td><p>Cell one</p></td>" +
                    "<td><p>Cell two</p></td></tr></tbody></table>",
            )
        }
        composeEditor(note.id)
        enterEditMode()

        // Caret in the body paragraph — again the only rich editor
        // focused in this test (see the split note above): the
        // full toolbar, block styles included.
        composeRule.onNodeWithText("Body para").performClick()
        awaitTrue("block styles with text focus") {
            exists("H1") { composeRule.onNodeWithText("H1").assertExists() }
        }
        composeRule.onNodeWithText("B").assertExists()
    }

    @Test
    fun editMode_structuralTableOp_clearsCellToolbarRouting() {
        // Codex P2 (PR #41): a structural table op evicts the cell
        // states — the screen's focused-cell route must clear with
        // them, so the block-style row comes back instead of the
        // toolbar silently aiming at an evicted cell state.
        val note = runBlocking {
            repository.createRichNote(
                title = "Routing clear",
                contentHtml = "<p>Body para</p>" +
                    "<table><tbody><tr><td><p>Cell one</p></td>" +
                    "<td><p>Cell two</p></td></tr></tbody></table>",
            )
        }
        composeEditor(note.id)
        enterEditMode()

        composeRule.onNodeWithText("Cell one").performClick()
        awaitTrue("block styles hidden with cell focus") {
            exists("no H1") { composeRule.onNodeWithText("H1").assertDoesNotExist() }
        }
        // The at-cell operations row is showing for the focused
        // cell; "Row below" is a structural op (it evicts the
        // cell states).
        composeRule.onNodeWithText("Row below").performClick()
        awaitTrue("block styles back after the structural op") {
            exists("H1") { composeRule.onNodeWithText("H1").assertExists() }
        }
    }

    @Test
    fun editMode_untouchedRichCells_persistByteIdentical() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Untouched",
                contentHtml = "<table><tbody><tr><th>Head</th></tr>" +
                    "<tr><td><p><strong>Bold cell</strong></p></td></tr>" +
                    "<tr><td><p>Plain cell</p></td></tr></tbody></table>",
            )
        }
        composeEditor(note.id)
        enterEditMode()

        // Focus a cell (composing its rich state) but change
        // nothing, then leave edit mode — a persist point.
        composeRule.onNodeWithText("Bold cell").performClick()
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("back to read mode") {
            exists("edit door") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        val html = storedHtml(note.id)
        assertTrue(
            "an untouched rich cell keeps its stored bytes: $html",
            html.contains("<td><p><strong>Bold cell</strong></p></td>"),
        )
        assertTrue(
            "an untouched plain cell keeps its stored bytes: $html",
            html.contains("<td><p>Plain cell</p></td>"),
        )
    }

    /** Replaces the text of the editable field currently showing
     *  [current] — the EditorBlockDragUiTest setFieldText pattern. */
    private fun replaceFieldText(current: String, replacement: String) {
        val nodes = composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        val index = nodes.indexOfFirst {
            it.config.getOrNull(SemanticsProperties.EditableText)?.text == current
        }
        if (index < 0) fail("no editable field showing '$current'")
        composeRule.onAllNodes(hasSetTextAction())[index]
            .performTextReplacement(replacement)
    }

    @Test
    fun editMode_twoCellsEditedInSequence_bothPersist() {
        // Codex P1 (PR #41): RichCellText's change collector must
        // call the LATEST onEdit — with the first-composed callback,
        // editing a second cell wrote from the stale table snapshot
        // and silently reverted the first cell's edit.
        val note = runBlocking {
            repository.createRichNote(
                title = "Two cells",
                contentHtml = "<table><tbody><tr><td><p>Cell one</p></td>" +
                    "<td><p>Cell two</p></td></tr></tbody></table>",
            )
        }
        composeEditor(note.id)
        enterEditMode()

        replaceFieldText("Cell one", "First edited")
        replaceFieldText("Cell two", "Second edited")

        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("back to read mode") {
            exists("edit door") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        val html = storedHtml(note.id)
        assertTrue("first cell edit persists: $html", html.contains("First edited"))
        assertTrue("second cell edit persists: $html", html.contains("Second edited"))
    }

    // ------------------------------------------------------ Part B

    @Test
    fun readMode_nestedTasks_renderAndChildCheckboxToggles() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Nested",
                contentHtml = "<ul data-type=\"taskList\">" +
                    "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Parent task</p>" +
                    "<ul data-type=\"taskList\">" +
                    "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Child task</p></div></li>" +
                    "</ul></div></li></ul>",
            )
        }
        composeEditor(note.id)

        awaitTrue("nested tasks rendered in read mode") {
            exists("parent") {
                composeRule.onNodeWithText("Parent task").assertIsDisplayed()
            } && exists("child") {
                composeRule.onNodeWithText("Child task").assertIsDisplayed()
            }
        }
        // Two toggleable checkboxes: parent first, child second.
        val toggles = composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes()
        org.junit.Assert.assertEquals(2, toggles.size)

        // Toggle the CHILD's checkbox while reading.
        composeRule.onAllNodes(isToggleable())[1].performClick()
        awaitTrue("child toggled on, parent untouched") {
            composeRule.onAllNodes(isToggleable())[1].fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On &&
                composeRule.onAllNodes(isToggleable())[0].fetchSemanticsNode().config
                    .getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.Off
        }
        // The toggle only schedules the 1.5 s autosave, which does
        // not tick on Robolectric's virtual clock — drive the
        // explicit persist point instead: round-trip through edit
        // mode and press Done (the pattern
        // editMode_untouchedRichCells_persistByteIdentical uses).
        enterEditMode()
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("back to read mode") {
            exists("edit door") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        val html = storedHtml(note.id)
        assertTrue(
            "child persisted checked, parent untouched: $html",
            html.contains("data-checked=\"true\"><div><p>Child task</p>") &&
                html.contains("data-checked=\"false\"><div><p>Parent task</p>"),
        )
    }

    @Test
    fun editMode_indentThenAddSubItem_persistsNestedShape() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Nesting",
                contentHtml = "<ul data-type=\"taskList\">" +
                    "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Task A</p></div></li>" +
                    "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Task B</p></div></li></ul>",
            )
        }
        composeEditor(note.id)
        enterEditMode()

        // Focus Task B's field: the footer offers Indent (it has a
        // previous sibling) but not Outdent (it is top-level).
        composeRule.onNodeWithText("Task B").performClick()
        awaitTrue("Indent offered for Task B") {
            exists("indent") { composeRule.onNodeWithText("Indent").assertExists() }
        }
        composeRule.onNodeWithText("Outdent").assertDoesNotExist()

        composeRule.onNodeWithText("Indent").performClick()
        // Indent only schedules the 1.5 s autosave, which does not
        // tick on Robolectric's virtual clock — persist explicitly
        // via Done (the editMode_untouchedRichCells pattern) before
        // asserting storage.
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("back to read mode") {
            exists("edit door") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        val nestedHtml = storedHtml(note.id)
        assertTrue(
            "Task B nested under Task A in storage: $nestedHtml",
            nestedHtml.contains("<p>Task A</p><ul data-type=\"taskList\">") &&
                nestedHtml.contains("<p>Task B</p>"),
        )

        // Back into edit mode and focus Task B at its nested path:
        // Add sub-item is now offered for it, and using it nests a
        // third level.
        enterEditMode()
        composeRule.onNodeWithText("Task B").performClick()
        awaitTrue("Add sub-item offered for nested Task B") {
            exists("add sub") { composeRule.onNodeWithText("Add sub-item").assertExists() }
        }
        composeRule.onNodeWithText("Add sub-item").performClick()
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("back to read mode again") {
            exists("edit door") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        assertTrue(
            "sub-item persisted as a third list level: ${storedHtml(note.id)}",
            storedHtml(note.id).split("<ul data-type=\"taskList\">").size - 1 == 3,
        )
    }
}
