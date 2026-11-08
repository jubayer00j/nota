package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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
 * Phase 6 (Editor Refinement #1) — UI-level proof for the two
 * items that live only in composition: the "Start writing…" body
 * placeholder (visible on an empty note, and never written into
 * the saved HTML) and the Image-from-URL dialog (an invalid URL
 * errors inline and inserts nothing; a valid one lands in the
 * note). Drives the REAL [RichNoteEditorScreen] over in-memory
 * Room, the EditorReadModeUiTest pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
    fun imageFromUrl_invalidUrlErrorsAndInsertsNothing_validUrlInserts() {
        val note = runBlocking {
            repository.createRichNote(
                title = "URL Image Note",
                contentHtml = "<p>Body text</p>",
            )
        }
        composeEditor(note.id)

        awaitTrue("body rendered") {
            exists("body") {
                composeRule.onNodeWithText("Body text").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Body text").performClick()
        awaitTrue("edit mode") {
            exists("done") {
                composeRule.onNodeWithContentDescription("Done editing").assertIsDisplayed()
            }
        }

        // Insert menu -> Image from URL…
        composeRule.onNodeWithContentDescription("Insert block").performClick()
        awaitTrue("insert menu open") {
            exists("url item") {
                composeRule.onNodeWithText("Image from URL…").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Image from URL…").performClick()
        awaitTrue("url dialog open") {
            exists("url field") {
                composeRule.onNodeWithTag("imageUrlField").assertIsDisplayed()
            }
        }

        // A scheme-less URL: inline error, dialog stays, and the
        // stored note gains no image.
        composeRule.onNodeWithTag("imageUrlField")
            .performTextInput("example.com/pic.png")
        composeRule.onNodeWithText("Insert").performClick()
        awaitTrue("inline error shown") {
            exists("error") {
                composeRule.onNodeWithText("Enter a valid http(s) image URL")
                    .assertIsDisplayed()
            }
        }
        assertFalse(storedHtml(note.id).contains("example.com"))

        // Correct it to a valid https URL: the dialog closes and
        // the remote image lands in the note's HTML (autosave).
        composeRule.onNodeWithTag("imageUrlField").performTextClearance()
        composeRule.onNodeWithTag("imageUrlField")
            .performTextInput("https://example.com/pic.png")
        composeRule.onNodeWithText("Insert").performClick()
        awaitTrue("image persisted in the note html") {
            storedHtml(note.id).contains("https://example.com/pic.png")
        }
        assertTrue(storedHtml(note.id).contains("<img"))
    }
}
