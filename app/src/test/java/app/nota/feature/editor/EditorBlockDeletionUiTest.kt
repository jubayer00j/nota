package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nota.data.attachments.AttachmentStore
import app.nota.data.attachments.PendingInsertStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.html.NoteDocument
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
 * Phase 6 (Editor Refinement #1 fix) — Jubayer's device-test bug:
 * a code block, once inserted, could not be cancelled or removed,
 * and his ruling is general — EVERY block must be deletable. The
 * audit (BLOCK-DELETION-AUDIT.md) found exactly two block types
 * with no edit-mode removal path: code (a bare field whose onRemove
 * went unwired) and quote (same unwired onRemove in its row). Both
 * now carry the editor's standard ✕ remove affordance — code inside
 * BlockShell ("Remove block"), quote inline in its row
 * ("Remove quote") — flowing through the unchanged removeBlock.
 *
 * These tests drive the REAL [RichNoteEditorScreen] over in-memory
 * Room (the EditorRefinementUiTest harness): seed a note holding
 * the block, enter edit mode, tap the block's ✕, leave edit mode
 * (the persist point) and read the stored document back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorBlockDeletionUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Raw-key device provider, the EditorRefinementUiTest pattern. */
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

    /** Compose the editor and walk it into edit mode via the pencil. */
    private fun composeEditorInEditMode(noteId: String) {
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
        // The screen opens in READ mode (Phase 3.6, G3): enter edit
        // mode through the pencil, like a user would (the
        // EditorBlockDragUiTest entry).
        awaitTrue("read mode composed") {
            exists("edit door") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithContentDescription("Edit note").performClick()
        awaitTrue("edit mode composed") {
            exists("done door") {
                composeRule.onNodeWithContentDescription("Done editing").assertIsDisplayed()
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

    private fun storedKinds(noteId: String): List<String> =
        NoteDocument.parse(storedHtml(noteId)).map { it::class.simpleName ?: "?" }

    /**
     * Tap the named remove affordance, leave edit mode (the persist
     * point) and wait until the stored document reflects [removed].
     */
    private fun removeAndPersist(noteId: String, removeDescription: String, removed: () -> Boolean) {
        awaitTrue("remove affordance '$removeDescription' visible") {
            exists(removeDescription) {
                composeRule.onNodeWithContentDescription(removeDescription).assertIsDisplayed()
            }
        }
        composeRule.onNodeWithContentDescription(removeDescription).performClick()
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("stored document updated") { removed() }
    }

    @Test
    fun codeBlock_removeButton_deletesBlockAndPersists() {
        // The reported regression, named for it: a code block with
        // content, between two paragraphs, must be removable and
        // the removal must persist with the neighbours intact.
        val note = runBlocking {
            repository.createRichNote(
                title = "Code Note",
                contentHtml = "<p>Before</p><pre><code>val x = 1</code></pre><p>After</p>",
            )
        }
        composeEditorInEditMode(note.id)
        removeAndPersist(note.id, "Remove block") {
            !storedHtml(note.id).contains("<pre") &&
                storedKinds(note.id).none { it == "CodeSegment" }
        }
        val html = storedHtml(note.id)
        assertFalse(html.contains("val x = 1"))
        // The rest of the document survives the removal.
        val plain = NoteDocument.plainText(NoteDocument.parse(html))
        assertTrue("neighbours kept: $plain", plain.contains("Before") && plain.contains("After"))
    }

    @Test
    fun emptyCodeBlock_removeButton_deletesItWithoutTyping() {
        // The insert menu creates CodeSegment("") — a user who
        // inserts one and immediately wants it gone must succeed
        // WITHOUT typing anything (an empty block was exactly the
        // stuck case: emptying could never delete it either).
        val note = runBlocking {
            repository.createRichNote(
                title = "Empty Code Note",
                contentHtml = "<pre><code></code></pre>",
            )
        }
        composeEditorInEditMode(note.id)
        removeAndPersist(note.id, "Remove block") {
            !storedHtml(note.id).contains("<pre") &&
                storedKinds(note.id).none { it == "CodeSegment" }
        }
    }

    @Test
    fun quoteBlock_removeButton_deletesBlockAndPersists() {
        // The audit's second gap: a quote had no removal path
        // either — its ✕ now sits at the end of the quote's row.
        val note = runBlocking {
            repository.createRichNote(
                title = "Quote Note",
                contentHtml = "<p>Intro</p><blockquote><p>Quoted words</p></blockquote><p>Outro</p>",
            )
        }
        composeEditorInEditMode(note.id)
        removeAndPersist(note.id, "Remove quote") {
            !storedHtml(note.id).contains("<blockquote") &&
                storedKinds(note.id).none { it == "QuoteSegment" }
        }
        val plain = NoteDocument.plainText(NoteDocument.parse(storedHtml(note.id)))
        assertTrue("neighbours kept: $plain", plain.contains("Intro") && plain.contains("Outro"))
        assertFalse(plain.contains("Quoted words"))
    }
}
