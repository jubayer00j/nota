package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
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
 * Phase 6 (Editor Refinement #1) — UI-level proof for the item
 * that lives only in composition: the "Start writing…" body
 * placeholder (visible on an empty note in edit mode, and never
 * written into the saved HTML). Drives the REAL
 * [RichNoteEditorScreen] over in-memory Room, the
 * EditorReadModeUiTest pattern.
 *
 * The Image-from-URL dialog deliberately has NO UI test here:
 * it is reached only through the toolbar's DropdownMenu, and
 * popup-window semantics (menus and dialogs) never enter the
 * forest the compose test rule can search under this harness's
 * Robolectric setup — proven on PR #34's gate attempts 1–2 with
 * two query styles (assertIsDisplayed and existence via
 * fetchSemanticsNodes), both timing out on the menu's first
 * item; consistently, no other test in this suite drives a
 * DropdownMenu. The dialog's decision logic is unit-covered
 * instead (EditorRefinementTest's parseRemoteImageUrl cases),
 * and its insert runs through the photo picker's own pre-existing
 * caret-insert funnel.
 *
 * PR #50: the "Turn into" conversion menu is an INLINE strip
 * under the toolbar for exactly that reason — a popup menu's
 * targets would be untestable here. Its offering and conversions
 * are driven end-to-end below.
 *
 * The window is extra wide so the whole formatting toolbar —
 * including the conversion actions, which sit far along its
 * horizontally scrolling row — is on screen for real clicks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1080dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorRefinementUiTest {

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

    private fun composeEditor(noteId: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vm = RichNoteEditorViewModel(
            repository,
            AttachmentStore(context, provider),
            app.nota.data.templates.TemplateRepository(context),
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

    @Test
    fun emptyNote_showsStartWritingPlaceholder_andPlaceholderNeverPersists() {
        val note = runBlocking {
            repository.createRichNote(title = "Empty Note", contentHtml = "")
        }
        composeEditor(note.id)

        // Into edit mode: an empty note may open there directly;
        // otherwise the pencil is the door (EditorReadModeUiTest).
        awaitTrue("edit door or placeholder") {
            exists("placeholder") {
                composeRule.onNodeWithText("Start writing…").assertIsDisplayed()
            } || exists("edit door") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        if (!exists("placeholder") {
                composeRule.onNodeWithText("Start writing…").assertIsDisplayed()
            }
        ) {
            composeRule.onNodeWithContentDescription("Edit note").performClick()
        }
        awaitTrue("placeholder visible in edit mode") {
            exists("placeholder") {
                composeRule.onNodeWithText("Start writing…").assertIsDisplayed()
            }
        }

        // Leave edit mode (a persist point) and read the stored
        // body back: the placeholder is an overlay, never content.
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("back to read mode") {
            exists("edit door") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        assertFalse(
            "placeholder text must never be serialised",
            storedHtml(note.id).contains("Start writing"),
        )
    }

    @Test
    fun turnIntoTaskListAndBack_roundTripsThroughTheToolbar() {
        // Editor Refinement #2's paragraph <-> task conversion,
        // since PR #50 driven through the "Turn into" menu: the
        // note is one plain paragraph; the caret lands in it,
        // "Turn into" -> "Task list" converts it, and focusing the
        // new item offers "Turn into" -> "Paragraph", which
        // converts it straight back. (Adapted from
        // makeTaskThenMakeText_roundTripsThroughTheToolbar, which
        // clicked the old single "Make task" / "Make text" action.)
        val note = runBlocking {
            repository.createRichNote(
                title = "Convertible",
                contentHtml = "<p>Make me a task</p>",
            )
        }
        composeEditor(note.id)

        // Into edit mode via the pencil (the only door).
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

        // Caret into the paragraph -> the toolbar offers Turn into;
        // expanding it offers Task list for a plain paragraph.
        composeRule.onNodeWithText("Make me a task").performClick()
        awaitTrue("Turn into offered") {
            exists("turn into") {
                composeRule.onNodeWithText("Turn into").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Turn into").performClick()
        awaitTrue("Task list offered") {
            exists("task list") {
                composeRule.onNodeWithText("Task list").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Task list").performClick()

        // The stored body is now a task list holding the paragraph.
        awaitTrue("stored as a task list") {
            val html = storedHtml(note.id)
            html.contains("taskList") && html.contains("Make me a task")
        }

        // Focus the task item's field -> Turn into offers
        // Paragraph; taking it restores the plain paragraph.
        composeRule.onNodeWithText("Make me a task").performClick()
        awaitTrue("Turn into offered for the item") {
            exists("turn into") {
                composeRule.onNodeWithText("Turn into").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Turn into").performClick()
        awaitTrue("Paragraph offered") {
            exists("paragraph") {
                composeRule.onNodeWithText("Paragraph").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Paragraph").performClick()
        awaitTrue("stored as a plain paragraph again") {
            val html = storedHtml(note.id)
            html.contains("<p>Make me a task</p>") && !html.contains("taskList")
        }
    }

    @Test
    fun turnIntoQuoteAndBack_throughTheToolbar() {
        // PR #50: paragraph -> quote -> paragraph through the
        // "Turn into" strip, asserting the stored shapes.
        val note = runBlocking {
            repository.createRichNote(
                title = "Quotable",
                contentHtml = "<p>Quote me</p>",
            )
        }
        composeEditor(note.id)

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

        composeRule.onNodeWithText("Quote me").performClick()
        awaitTrue("Turn into offered") {
            exists("turn into") {
                composeRule.onNodeWithText("Turn into").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Turn into").performClick()
        awaitTrue("Quote offered") {
            exists("quote") {
                composeRule.onNodeWithText("Quote").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Quote").performClick()
        awaitTrue("stored as a quote") {
            storedHtml(note.id).contains("<blockquote>Quote me</blockquote>")
        }

        // The quote itself, focused, offers Paragraph — and NOT a
        // direct Code block (quote <-> code routes via Paragraph).
        composeRule.onNodeWithText("Quote me").performClick()
        awaitTrue("Turn into offered for the quote") {
            exists("turn into") {
                composeRule.onNodeWithText("Turn into").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Turn into").performClick()
        awaitTrue("Paragraph offered for the quote") {
            exists("paragraph") {
                composeRule.onNodeWithText("Paragraph").assertIsDisplayed()
            }
        }
        assertFalse(
            "quote must not offer a direct Code block conversion",
            exists("code block") {
                composeRule.onNodeWithText("Code block").assertIsDisplayed()
            },
        )
        composeRule.onNodeWithText("Paragraph").performClick()
        awaitTrue("stored as a plain paragraph again") {
            val html = storedHtml(note.id)
            html.contains("<p>Quote me</p>") && !html.contains("blockquote")
        }
    }

    @Test
    fun turnInto_emptyQuoteOffersParagraph() {
        // Codex PR #50 R1 (P2): a newly inserted or cleared quote
        // holds no text, and the Turn into eligibility used to
        // require non-empty text on the quote branch — so an empty
        // quote offered no Paragraph conversion, only deletion.
        // quoteToText itself has no content precondition; the offer
        // must not either (the code branch never had the check).
        val note = runBlocking {
            repository.createRichNote(
                title = "Empty quote",
                contentHtml = "<blockquote><p></p></blockquote>",
            )
        }
        composeEditor(note.id)

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

        // Focus the empty quote field: the SetText nodes are the
        // title first, then the quote's own field (empty text).
        awaitTrue("quote field present") {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2
        }
        composeRule.onAllNodes(hasSetTextAction())[1].performClick()
        awaitTrue("Turn into offered for the empty quote") {
            exists("turn into") {
                composeRule.onNodeWithText("Turn into").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Turn into").performClick()
        awaitTrue("Paragraph offered for the empty quote") {
            exists("paragraph") {
                composeRule.onNodeWithText("Paragraph").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Paragraph").performClick()
        awaitTrue("empty quote stored as text, blockquote gone") {
            !storedHtml(note.id).contains("blockquote")
        }
    }

    @Test
    fun turnIntoCodeAndBack_throughTheToolbar() {
        // PR #50: paragraph -> code block -> paragraph through the
        // "Turn into" strip.
        val note = runBlocking {
            repository.createRichNote(
                title = "Codable",
                contentHtml = "<p>Code me</p>",
            )
        }
        composeEditor(note.id)

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

        composeRule.onNodeWithText("Code me").performClick()
        awaitTrue("Turn into offered") {
            exists("turn into") {
                composeRule.onNodeWithText("Turn into").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Turn into").performClick()
        awaitTrue("Code block offered") {
            exists("code block") {
                composeRule.onNodeWithText("Code block").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Code block").performClick()
        awaitTrue("stored as a code block") {
            storedHtml(note.id).contains("<pre><code>Code me</code></pre>")
        }

        // Focus the code field -> Turn into offers Paragraph.
        composeRule.onNodeWithText("Code me").performClick()
        awaitTrue("Turn into offered for the code block") {
            exists("turn into") {
                composeRule.onNodeWithText("Turn into").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Turn into").performClick()
        awaitTrue("Paragraph offered for the code block") {
            exists("paragraph") {
                composeRule.onNodeWithText("Paragraph").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Paragraph").performClick()
        awaitTrue("stored as a plain paragraph again") {
            val html = storedHtml(note.id)
            html.contains("<p>Code me</p>") && !html.contains("<pre>")
        }
    }

    @Test
    fun turnInto_headingOffersQuoteAndCodeButNotTaskList() {
        // PR #50 scope pin: a heading converts to Quote and Code
        // block, but the task conversion stays plain-paragraph-only
        // — "Task list" must not be offered with the caret in a
        // heading. The fixture is heading-ONLY on purpose: a rich
        // field's semantics text is its segment's WHOLE text, so a
        // following paragraph would make the "Head line" query
        // match nothing (the two blocks share one text node).
        val note = runBlocking {
            repository.createRichNote(
                title = "Headed",
                contentHtml = "<h2>Head line</h2>",
            )
        }
        composeEditor(note.id)

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

        composeRule.onNodeWithText("Head line").performClick()
        awaitTrue("Turn into offered") {
            exists("turn into") {
                composeRule.onNodeWithText("Turn into").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Turn into").performClick()
        awaitTrue("Quote offered for the heading") {
            exists("quote") {
                composeRule.onNodeWithText("Quote").assertIsDisplayed()
            }
        }
        assertFalse(
            "a heading must not offer the task conversion",
            exists("task list") {
                composeRule.onNodeWithText("Task list").assertIsDisplayed()
            },
        )
        composeRule.onNodeWithText("Quote").performClick()
        awaitTrue("stored as a quote") {
            storedHtml(note.id).contains("<blockquote>Head line</blockquote>")
        }
    }

}
