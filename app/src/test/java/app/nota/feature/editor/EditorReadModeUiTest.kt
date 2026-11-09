package app.nota.feature.editor

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isToggleable
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
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteLockRepository
import app.nota.data.repository.NoteRepository
import app.nota.data.security.NoteLockBiometricStore
import app.nota.data.security.NoteLockCrypto
import app.nota.data.security.NoteLockDeviceKeyProvider
import app.nota.data.security.NoteLockMethod
import app.nota.data.security.BiometricStep
import app.nota.data.security.StepResult
import app.nota.data.templates.TemplateRepository
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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

/**
 * Phase 3.6 (G3) — read mode, UI-level. Drives the REAL
 * [RichNoteEditorScreen] over real (in-memory) Room, the
 * NotesSwipeGhostUiTest pattern: a note must OPEN for reading (no
 * toolbar, no caret — Noma parity), ✓ must return to reading the
 * same note, task checkboxes must toggle while reading, and a
 * locked note must show its gate — never the content, never an
 * edit door.
 *
 * Phase 6 (Editor Refinement #2): read mode is READ-ONLY — a tap
 * on the body must NOT enter edit mode any more (the pencil is the
 * only door), and a completed task wears the Tinted Card DONE pill
 * while reading.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorReadModeUiTest {

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

    @Test
    fun noteOpensInReadMode_tapBodyStaysReading_pencilEdits_checkReturnsToRead() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Reading Note",
                contentHtml = "<p>Reading body text</p>" +
                    "<ul data-type=\"taskList\">" +
                    "<li data-type=\"taskItem\" data-checked=\"false\">" +
                    "<p>Water the plants</p></li></ul>" +
                    "<p>Closing paragraph</p>",
            )
        }
        composeEditor(note.id)

        // Opens in READ mode: body visible, the pencil is the edit
        // door, and there is no "Done editing" (no edit session).
        awaitTrue("body rendered in read mode") {
            exists("body") {
                composeRule.onNodeWithText("Reading body text").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Done editing").assertDoesNotExist()
        // The one task is unchecked: no DONE pill anywhere.
        composeRule.onNodeWithText("DONE").assertDoesNotExist()

        // Task checkboxes toggle while reading (Noma parity) — and
        // the toggled-on task immediately wears the DONE pill.
        val toggle = composeRule.onNode(isToggleable())
        val before = toggle.fetchSemanticsNode().config
            .getOrNull(SemanticsProperties.ToggleableState)
        org.junit.Assert.assertEquals(ToggleableState.Off, before)
        toggle.performClick()
        awaitTrue("task toggled on in read mode") {
            composeRule.onNode(isToggleable()).fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
        }
        composeRule.onNodeWithText("DONE").assertIsDisplayed()

        // Tap the body → STILL read mode (Editor Refinement #2:
        // read mode has no tap doors; the pencil is the only one).
        composeRule.onNodeWithText("Reading body text").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Done editing").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()

        // Tap the title → still read mode too.
        composeRule.onNodeWithText("Reading Note").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Done editing").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()

        // The pencil → edit mode.
        composeRule.onNodeWithContentDescription("Edit note").performClick()
        awaitTrue("edit mode after pencil tap") {
            exists("done") {
                composeRule.onNodeWithContentDescription("Done editing").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithContentDescription("Edit note").assertDoesNotExist()
        // The checked task keeps its DONE pill while editing.
        composeRule.onNodeWithText("DONE").assertIsDisplayed()

        // ✓ → back to READ mode on the same note.
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("read mode after check") {
            exists("edit") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Reading body text").assertIsDisplayed()
    }

    @Test
    fun brandNewNote_startsInEditMode() {
        // Editor Refinement #2 (fix round): a note created this
        // moment opens IN the editor — the navigation layer passes
        // startInEdit for creation flows only; an existing note
        // (the flow test above) opens for reading.
        val note = runBlocking {
            repository.createRichNote(
                title = "Fresh Note",
                contentHtml = "<p></p>",
            )
        }
        composeEditor(note.id, startInEdit = true)
        awaitTrue("edit mode from the start") {
            exists("done") {
                composeRule.onNodeWithContentDescription("Done editing").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithContentDescription("Edit note").assertDoesNotExist()
    }

    @Test
    fun checkedTaskInReadMode_wearsTheDonePill_uncheckedDoesNot() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Chores",
                contentHtml = "<ul data-type=\"taskList\">" +
                    "<li data-type=\"taskItem\" data-checked=\"true\">" +
                    "<p>Finished chore</p></li>" +
                    "<li data-type=\"taskItem\" data-checked=\"false\">" +
                    "<p>Open chore</p></li></ul>",
            )
        }
        composeEditor(note.id)

        awaitTrue("read mode with both tasks") {
            exists("finished") {
                composeRule.onNodeWithText("Finished chore").assertIsDisplayed()
            } && exists("open") {
                composeRule.onNodeWithText("Open chore").assertIsDisplayed()
            }
        }
        // Exactly one DONE pill — the checked item's.
        composeRule.onNodeWithText("DONE").assertIsDisplayed()
        val states = composeRule.onAllNodes(isToggleable())
            .fetchSemanticsNodes().map {
                it.config.getOrNull(SemanticsProperties.ToggleableState)
            }
        org.junit.Assert.assertEquals(
            listOf(ToggleableState.On, ToggleableState.Off),
            states,
        )
    }

    @Test
    fun lockedNote_showsGateNotContent() {
        val note = runBlocking {
            val created = repository.createRichNote(
                title = "Secret Note",
                contentHtml = "<p>Secret body text</p>",
            )
            lockRepository.lockNote(
                created.id, NoteLockMethod.PIN, NoteLockCrypto.canonicalPin("135790"),
            )
            created
        }
        composeEditor(note.id)

        // The gate comes first: no body, no edit door, no read view.
        awaitTrue("gate composed") {
            exists("gate title") {
                composeRule.onNodeWithText("Secret Note").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Secret body text").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Edit note").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Done editing").assertDoesNotExist()
    }

    @Test
    fun chartNote_rendersChartTitleInReadMode() {
        // Charts PR-A: a stored v2 chart div renders as a chart in
        // read mode — its title is real text on the chart — instead
        // of the old "preserved as-is" placeholder card.
        val chartDiv = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"bar\" data-title=\"Quarterly Sales\" data-subtitle=\"\" " +
            "data-categories='[\"Q1\",\"Q2\",\"Q3\"]' " +
            "data-series='[{\"name\":\"Revenue\",\"values\":[10,25,15]}]'></div>"
        val note = runBlocking {
            repository.createRichNote(
                title = "Charts",
                contentHtml = "<p>Intro</p>$chartDiv",
            )
        }
        composeEditor(note.id)

        awaitTrue("chart title rendered in read mode") {
            exists("chart title") {
                composeRule.onNodeWithText("Quarterly Sales").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithText("Chart — preserved as-is.").assertDoesNotExist()
    }

    @Test
    fun pieChart_composesInReadMode() {
        val pie = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"pie\" data-title=\"Share\" data-subtitle=\"\" " +
            "data-categories='[\"A\",\"B\"]' " +
            "data-series='[{\"name\":\"S\",\"values\":[60,40]}]'></div>"
        val note = runBlocking {
            repository.createRichNote(title = "Pie", contentHtml = pie)
        }
        composeEditor(note.id)

        awaitTrue("pie chart rendered") {
            exists("pie title") {
                composeRule.onNodeWithText("Share").assertIsDisplayed()
            }
        }
    }

    @Test
    fun radarChart_composesInReadMode() {
        val radar = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"radar\" data-title=\"Skills\" data-subtitle=\"\" " +
            "data-categories='[\"Speed\",\"Power\",\"Range\"]' " +
            "data-series='[{\"name\":\"S\",\"values\":[3,5,4]}]'></div>"
        val note = runBlocking {
            repository.createRichNote(title = "Radar", contentHtml = radar)
        }
        composeEditor(note.id)

        awaitTrue("radar chart rendered") {
            exists("radar title") {
                composeRule.onNodeWithText("Skills").assertIsDisplayed()
            }
        }
    }
}
