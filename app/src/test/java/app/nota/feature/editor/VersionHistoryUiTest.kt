package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.nota.data.attachments.AttachmentStore
import app.nota.data.attachments.PendingInsertStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.db.NoteVersionEntity
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
 * Version history (Phase 6, PR #43) — UI-level. Drives the REAL
 * [RichNoteEditorScreen] over real (in-memory) Room, the
 * EditorAutolinkPopupUiTest pattern: overflow → "Version history"
 * → list → read-only preview → inline restore confirm → the
 * editor shows the restored text; then the clear flow empties
 * the history in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VersionHistoryUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** Raw-key device provider, the EditorAutolinkPopupUiTest pattern. */
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

    private lateinit var db: NotaDatabase
    private lateinit var provider: DatabaseProvider
    private lateinit var repository: NoteRepository
    private lateinit var lockRepository: NoteLockRepository
    private lateinit var insertStore: PendingInsertStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
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

    private fun exists(finder: () -> Unit): Boolean = try {
        finder()
        true
    } catch (e: AssertionError) {
        false
    }

    private fun displayed(text: String) = exists {
        composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun openHistory() {
        composeRule.onNodeWithContentDescription("More").performClick()
        awaitTrue("overflow shows Version history") { displayed("Version history") }
        composeRule.onNodeWithText("Version history").performClick()
    }

    @Test
    fun versionHistory_listPreviewRestore_andClear() {
        val now = System.currentTimeMillis()
        val note = runBlocking {
            repository.createRichNote(
                title = "History Note",
                contentHtml = "<p>current body text</p>",
            )
        }
        runBlocking {
            db.noteVersionDao().insert(
                NoteVersionEntity(
                    id = "v-first", noteId = note.id, title = "First Draft",
                    contentHtml = "<p>first body text</p>",
                    createdAt = now - 300_000,
                ),
            )
            db.noteVersionDao().insert(
                NoteVersionEntity(
                    id = "v-earlier", noteId = note.id, title = "Earlier Draft",
                    contentHtml = "<p>earlier body text</p>",
                    createdAt = now - 120_000,
                ),
            )
        }
        composeEditor(note.id)

        // --- list state
        openHistory()
        awaitTrue("dialog lists both versions with the device footer") {
            displayed("Earlier Draft") && displayed("First Draft") &&
                displayed("History is kept on this device.")
        }

        // --- preview state (read-only render of the stored HTML)
        composeRule.onNodeWithText("Earlier Draft").performClick()
        awaitTrue("preview shows the version body + restore action") {
            displayed("earlier body text") && displayed("Restore this version")
        }

        // --- inline restore confirm
        composeRule.onNodeWithText("Restore this version").performClick()
        awaitTrue("restore confirm panel") {
            displayed("Replace the current text with this version?")
        }
        composeRule.onNodeWithText("Restore").performClick()

        // --- dialog gone; the read view shows the restored text,
        // and the stored row IS the version's bytes. (Await the
        // STORED row: the preview behind the confirm already shows
        // the same words, so text alone can't prove the restore
        // landed.)
        awaitTrue("stored row restored to the version") {
            runBlocking {
                repository.getById(note.id)?.contentHtml == "<p>earlier body text</p>"
            }
        }
        awaitTrue("editor reloaded with the restored body") {
            displayed("earlier body text")
        }
        val stored = runBlocking { repository.getById(note.id)!! }
        assertEquals("Earlier Draft", stored.title)
        // The restore kept the replaced state as the newest version.
        val after = runBlocking { db.noteVersionDao().getForNote(note.id) }
        assertEquals(3, after.size)
        assertEquals("<p>current body text</p>", after.first().contentHtml)

        // --- clear flow, in place
        openHistory()
        awaitTrue("history lists again after restore") { displayed("First Draft") }
        composeRule.onNodeWithText("Clear history").performClick()
        awaitTrue("clear confirm") { displayed("Clear all versions?") }
        composeRule.onNodeWithText("Clear").performClick()
        awaitTrue("empty state after clear") {
            displayed(
                "No earlier versions yet — versions appear as you edit, " +
                    "once a draft has had a moment to settle.",
            )
        }
        assertTrue(runBlocking { db.noteVersionDao().getForNote(note.id) }.isEmpty())
    }

    @Test
    fun versionHistory_restoreRefreshesFooterStats() {
        val now = System.currentTimeMillis()
        val note = runBlocking {
            repository.createRichNote(
                title = "Current Draft",
                contentHtml = "<p>one two three</p>",
            )
        }
        // Twelve one-letter words: the restored footer's count is
        // unmistakably different from the current 3, while the
        // preview stays as short on screen as the neighbouring
        // test's — a taller preview pushes the confirm panel's
        // Restore button to the dialog's bottom edge, where the
        // injected click lands on dead padding.
        val longHtml = "<p>a b c d e f g h i j k l</p>"
        runBlocking {
            db.noteVersionDao().insert(
                NoteVersionEntity(
                    id = "v-long", noteId = note.id, title = "Long Draft",
                    contentHtml = longHtml,
                    createdAt = now - 120_000,
                ),
            )
        }
        composeEditor(note.id)

        // The footer is seeded with the CURRENT version's counts.
        awaitTrue("footer seeded with the current version's stats") {
            displayed("3 words · 1 min read")
        }

        // Restore the long version through the real dialog flow.
        openHistory()
        awaitTrue("dialog lists the long version") { displayed("Long Draft") }
        composeRule.onNodeWithText("Long Draft").performClick()
        awaitTrue("preview shows the restore action") {
            displayed("Restore this version")
        }
        composeRule.onNodeWithText("Restore this version").performClick()
        awaitTrue("restore confirm panel") {
            displayed("Replace the current text with this version?")
        }
        composeRule.onNodeWithText("Restore").performClick()
        // The dialog dismisses itself only from the restore
        // completion callback — the dialog closing proves the
        // restore executed end to end before the row is checked.
        awaitTrue("dialog closes once the restore completes") {
            !displayed("Version history")
        }
        awaitTrue("stored row restored to the long version") {
            runBlocking { repository.getById(note.id)?.contentHtml == longHtml }
        }

        // Codex PR #54 R4 pin: the restore ITSELF refreshes the
        // footer — no edit follows. Pre-fix, the F1 dirty-only
        // tick never recomputed stats for a (non-dirty) restore,
        // so the footer kept the previous version's counts until
        // the next edit or reopen.
        awaitTrue("footer shows the restored version's stats without an edit") {
            displayed("12 words · 1 min read")
        }
        assertTrue(!displayed("3 words · 1 min read"))
    }

    @Test
    fun versionHistory_emptyState_forNoteWithoutHistory() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Fresh Note",
                contentHtml = "<p>only body text</p>",
            )
        }
        composeEditor(note.id)
        openHistory()
        awaitTrue("empty state composes") {
            displayed(
                "No earlier versions yet — versions appear as you edit, " +
                    "once a draft has had a moment to settle.",
            )
        }
    }
}
