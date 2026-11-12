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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import app.nota.data.attachments.AttachmentStore
import app.nota.data.attachments.PendingInsertStore
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.html.NoteDocument
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
import app.nota.feature.chart.CHART_BLOCK_EDIT_TAG
import app.nota.feature.chart.CHART_CANCEL_TAG
import app.nota.feature.chart.CHART_SAVE_TAG
import app.nota.feature.chart.CHART_TAB_CHART_TAG
import app.nota.feature.chart.CHART_TITLE_FIELD_TAG
import java.util.Base64
import java.util.concurrent.TimeUnit
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
import org.robolectric.Shadows.shadowOf
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
    fun stackedHugeBarChart_composesInReadMode() {
        // Two stacked 1e308 values: their sum overflows a double.
        // Stack accumulation saturates so the chart stays finite.
        val bar = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"bar\" data-title=\"Stacked Huge\" data-subtitle=\"\" " +
            "data-categories='[\"A\"]' " +
            "data-series='[{\"name\":\"S1\",\"values\":[1e308]},{\"name\":\"S2\",\"values\":[1e308]}]' " +
            "data-options='{\"stacked\":true}'></div>"
        val note = runBlocking {
            repository.createRichNote(title = "Stacked Huge note", contentHtml = bar)
        }
        composeEditor(note.id)

        awaitTrue("stacked huge chart rendered") {
            exists("stacked huge title") {
                composeRule.onNodeWithText("Stacked Huge").assertIsDisplayed()
            }
        }
    }

    @Test
    fun singleSeriesChart_showsLegendWithSeriesName() {
        // showLegend is honoured as persisted (1.x parity): even
        // a single-series chart lists its series name.
        val bar = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"bar\" data-title=\"Solo\" data-subtitle=\"\" " +
            "data-categories='[\"A\",\"B\"]' " +
            "data-series='[{\"name\":\"Revenue\",\"values\":[3,4]}]'></div>"
        val note = runBlocking {
            repository.createRichNote(title = "Solo note", contentHtml = bar)
        }
        composeEditor(note.id)

        awaitTrue("single-series legend rendered") {
            exists("series name in legend") {
                composeRule.onNodeWithText("Revenue").assertIsDisplayed()
            }
        }
    }

    @Test
    fun tinyValuesLineChart_composesInReadMode() {
        // [1e-12, 2e-12]: the axis must scale to the data's own
        // magnitude, not an absolute unit-wide fallback.
        val line = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"line\" data-title=\"Tiny Values\" data-subtitle=\"\" " +
            "data-categories='[\"A\",\"B\"]' " +
            "data-series='[{\"name\":\"S\",\"values\":[1e-12,2e-12]}]'></div>"
        val note = runBlocking {
            repository.createRichNote(title = "Tiny Values note", contentHtml = line)
        }
        composeEditor(note.id)

        awaitTrue("tiny values chart rendered") {
            exists("tiny values title") {
                composeRule.onNodeWithText("Tiny Values").assertIsDisplayed()
            }
        }
    }

    @Test
    fun oppositeExtremesLineChart_composesInReadMode() {
        // [-1e308, 1e308]: the axis span itself overflows a
        // double if computed unclamped, and nice-rounding the
        // endpoints overflows again — the scale is computed on a
        // unit domain so neither can happen.
        val line = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"line\" data-title=\"Opposite Extremes\" data-subtitle=\"\" " +
            "data-categories='[\"A\",\"B\"]' " +
            "data-series='[{\"name\":\"S\",\"values\":[-1e308,1e308]}]'></div>"
        val note = runBlocking {
            repository.createRichNote(title = "Opposite Extremes note", contentHtml = line)
        }
        composeEditor(note.id)

        awaitTrue("opposite extremes chart rendered") {
            exists("opposite extremes title") {
                composeRule.onNodeWithText("Opposite Extremes").assertIsDisplayed()
            }
        }
    }

    @Test
    fun constantHugeLineChart_composesInReadMode() {
        // Every value the same 1e308: the axis domain starts
        // zero-width, and a flat +1 widening cannot move a double
        // at that magnitude — the span must widen relatively.
        val line = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"line\" data-title=\"Flat Huge\" data-subtitle=\"\" " +
            "data-categories='[\"A\",\"B\"]' " +
            "data-series='[{\"name\":\"S\",\"values\":[1e308,1e308]}]'></div>"
        val note = runBlocking {
            repository.createRichNote(title = "Flat Huge note", contentHtml = line)
        }
        composeEditor(note.id)

        awaitTrue("flat huge chart rendered") {
            exists("flat huge title") {
                composeRule.onNodeWithText("Flat Huge").assertIsDisplayed()
            }
        }
    }

    @Test
    fun hugePieChart_composesInReadMode() {
        // Two 1e308 slices: the naive sum overflows to Infinity
        // and would blank the chart; the render scales first.
        val pie = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"pie\" data-title=\"Big Pie\" data-subtitle=\"\" " +
            "data-categories='[\"A\",\"B\"]' " +
            "data-series='[{\"name\":\"S\",\"values\":[1e308,1e308]}]'></div>"
        val note = runBlocking {
            repository.createRichNote(title = "Big Pie note", contentHtml = pie)
        }
        composeEditor(note.id)

        awaitTrue("huge pie rendered") {
            exists("huge pie title") {
                composeRule.onNodeWithText("Big Pie").assertIsDisplayed()
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

    @Test
    fun extremeValuesChart_composesInReadMode() {
        // ±1e308 are legal finite chart values; the axis maths
        // must survive them (the span overflows a Double if it is
        // computed unclamped) instead of crashing the note open.
        val line = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"line\" data-title=\"Extremes\" data-subtitle=\"\" " +
            "data-categories='[\"A\",\"B\",\"C\"]' " +
            "data-series='[{\"name\":\"S\",\"values\":[-1e308,1e308,0]}]'></div>"
        val note = runBlocking {
            repository.createRichNote(title = "Extreme note", contentHtml = line)
        }
        composeEditor(note.id)

        awaitTrue("extreme chart rendered") {
            exists("extreme title") {
                composeRule.onNodeWithText("Extremes").assertIsDisplayed()
            }
        }
    }

    // ---------------------------------------------------------------
    // Charts PR-B: tap-to-edit in edit mode

    private fun editableChartDiv(): String =
        "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"bar\" data-title=\"Quarterly Sales\" data-subtitle=\"\" " +
            "data-categories='[\"Q1\",\"Q2\",\"Q3\"]' " +
            "data-series='[{\"name\":\"Revenue\",\"values\":[10,25,15]}]'></div>"

    private fun storedHtml(noteId: String): String =
        runBlocking { repository.getById(noteId)?.contentHtml ?: "" }

    @Test
    fun chartInEditMode_tapOpensEditor_saveReplacesStoredBytes() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Editable Charts",
                contentHtml = "<p>Intro</p>${editableChartDiv()}",
            )
        }
        composeEditor(note.id, startInEdit = true)

        awaitTrue("chart edit target in edit mode") {
            exists("chart block") {
                composeRule.onNodeWithTag(CHART_BLOCK_EDIT_TAG).assertIsDisplayed()
            }
        }
        composeRule.onNodeWithTag(CHART_BLOCK_EDIT_TAG).performClick()
        awaitTrue("chart editor dialog open") {
            exists("chart tab") {
                composeRule.onNodeWithTag(CHART_TAB_CHART_TAG).assertIsDisplayed()
            }
        }
        composeRule.onNodeWithTag(CHART_TAB_CHART_TAG).performClick()
        composeRule.onNodeWithTag(CHART_TITLE_FIELD_TAG)
            .performTextReplacement("Annual Sales")
        composeRule.onNodeWithTag(CHART_SAVE_TAG).performClick()

        // ✓ is the persist point (the block-deletion suite's rule).
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("stored chart bytes replaced by the edit") {
            val stored = storedHtml(note.id)
            stored.contains("data-title=\"Annual Sales\"") &&
                !stored.contains("Quarterly Sales")
        }
        // And the replacement is a valid v2 chart with the data intact.
        val stored = storedHtml(note.id)
        val chartHtml = stored.substring(stored.indexOf("<div data-type=\"noma-chart\""))
        val parsed = app.nota.data.chart.ChartParser.parse(chartHtml)!!
        org.junit.Assert.assertEquals("Annual Sales", parsed.title)
        org.junit.Assert.assertEquals(listOf(10.0, 25.0, 15.0), parsed.series[0].values)
    }

    @Test
    fun chartInEditMode_cancelLeavesStoredBytesUntouched() {
        val chartDiv = editableChartDiv()
        val note = runBlocking {
            repository.createRichNote(
                title = "Untouched Charts",
                contentHtml = "<p>Intro</p>$chartDiv",
            )
        }
        composeEditor(note.id, startInEdit = true)

        awaitTrue("chart edit target in edit mode") {
            exists("chart block") {
                composeRule.onNodeWithTag(CHART_BLOCK_EDIT_TAG).assertIsDisplayed()
            }
        }
        composeRule.onNodeWithTag(CHART_BLOCK_EDIT_TAG).performClick()
        awaitTrue("chart editor dialog open") {
            exists("chart tab") {
                composeRule.onNodeWithTag(CHART_TAB_CHART_TAG).assertIsDisplayed()
            }
        }
        composeRule.onNodeWithTag(CHART_TAB_CHART_TAG).performClick()
        composeRule.onNodeWithTag(CHART_TITLE_FIELD_TAG)
            .performTextReplacement("Changed But Cancelled")
        composeRule.onNodeWithTag(CHART_CANCEL_TAG).performClick()

        // Persist whatever the document holds now; the chart's
        // stored bytes must still be the originals, verbatim.
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("back in read mode") {
            exists("edit") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        val stored = storedHtml(note.id)
        org.junit.Assert.assertTrue(stored.contains(chartDiv))
        org.junit.Assert.assertFalse(stored.contains("Changed But Cancelled"))
    }

    @Test
    fun unparseableChartBlock_hasNoEditAffordance() {
        // A div NoteDocument classifies as a chart block but the
        // chart parser cannot recognise: never editable, never
        // re-serialised — the honest placeholder only.
        val stray = "<div data-nota-block=\"chart\" data-payload=\"x\"></div>"
        val note = runBlocking {
            repository.createRichNote(
                title = "Stray Chart",
                contentHtml = "<p>Body</p>$stray",
            )
        }
        composeEditor(note.id, startInEdit = true)

        awaitTrue("preserved placeholder in edit mode") {
            exists("placeholder") {
                composeRule.onNodeWithText("Chart — preserved as-is.").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithTag(CHART_BLOCK_EDIT_TAG).assertDoesNotExist()
    }

    // ------------------------------------------------------------------
    // Performance chapter 2 pins (F1/F2/F4). The autosave tick's
    // delay is governed by the compose test clock in this harness
    // (LaunchedEffect delays do not follow the shadow looper), so
    // virtual time is advanced on BOTH clocks in small steps,
    // settling composition between steps.
    // ------------------------------------------------------------------

    private fun advanceVirtualTime(millis: Long) {
        val shadow = shadowOf(Looper.getMainLooper())
        var left = millis
        while (left > 0) {
            val step = minOf(left, 250L)
            // This MainTestClock has no advanceBy(millis); frames
            // are 16 ms, so step in whole frames (~256 ms each).
            repeat((step / 16).toInt() + 1) {
                composeRule.mainClock.advanceByFrame()
            }
            shadow.idleFor(step, TimeUnit.MILLISECONDS)
            composeRule.waitForIdle()
            left -= step
        }
    }

    @Test
    fun perfF2F4_readModeBuildsNoRichTextStates_andParsesOnce() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Perf shapes",
                contentHtml = "<p>Perf body text</p>" +
                    "<blockquote><p>Perf quoted line</p></blockquote>" +
                    "<table><tbody><tr><th>Head</th></tr>" +
                    "<tr><td>Perf cell</td></tr></tbody></table>" +
                    "<ul data-type=\"taskList\">" +
                    "<li data-type=\"taskItem\" data-checked=\"false\">" +
                    "<p>Perf task</p></li></ul>",
            )
        }
        EditorPerfProbe.reset()
        val parsesBefore = NoteDocument.parseInvocations
        composeEditor(note.id)
        awaitTrue("read mode shows every shape") {
            exists("shapes") {
                composeRule.onNodeWithText("Perf body text").assertIsDisplayed()
                composeRule.onNodeWithText("Perf quoted line").assertIsDisplayed()
                composeRule.onNodeWithText("Perf cell").assertIsDisplayed()
                composeRule.onNodeWithText("Perf task").assertIsDisplayed()
            }
        }
        // F2: text, quote, table cells and task rows all rendered
        // WITHOUT constructing a single RichTextState.
        org.junit.Assert.assertEquals(
            "read mode must construct zero RichTextStates",
            0,
            EditorPerfProbe.richStateConstructions,
        )
        // F4: the whole open cost exactly ONE NoteDocument.parse
        // (the ViewModel's load); the screen seeds from it.
        org.junit.Assert.assertEquals(
            "one parse per note open",
            1,
            NoteDocument.parseInvocations - parsesBefore,
        )
    }

    @Test
    fun perfF1_idleReadModeDoesNothing_togglePersistsWithExactlyOneSync() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Idle note",
                contentHtml = "<p>Idle body</p>" +
                    "<ul data-type=\"taskList\">" +
                    "<li data-type=\"taskItem\" data-checked=\"false\">" +
                    "<p>Idle task</p></li></ul>",
            )
        }
        composeEditor(note.id)
        awaitTrue("read mode body") {
            exists("body") {
                composeRule.onNodeWithText("Idle body").assertIsDisplayed()
            }
        }
        val before = runBlocking { repository.getById(note.id) }
        EditorPerfProbe.reset()

        // Two full tick windows pass with NO interaction: the
        // tick must do literally nothing — zero syncSegments
        // calls, and the stored row is byte-identical.
        advanceVirtualTime(3_400)
        org.junit.Assert.assertEquals(
            "an idle tick must not sync",
            0,
            EditorPerfProbe.syncSegmentsCalls,
        )
        org.junit.Assert.assertEquals(
            "an idle note must not be rewritten",
            before,
            runBlocking { repository.getById(note.id) },
        )

        // A read-mode task toggle marks the document dirty via
        // updateBlock; the next tick persists it with exactly ONE
        // sync, and later ticks go back to doing nothing.
        composeRule.onNode(isToggleable()).performClick()
        val deadline = System.currentTimeMillis() + 15_000
        var stored = runBlocking { repository.getById(note.id) }
        while (System.currentTimeMillis() < deadline &&
            stored?.contentHtml?.contains("data-checked=\"true\"") != true
        ) {
            advanceVirtualTime(400)
            stored = runBlocking { repository.getById(note.id) }
        }
        org.junit.Assert.assertTrue(
            "the toggle must persist via the tick: ${stored?.contentHtml}",
            stored?.contentHtml?.contains("data-checked=\"true\"") == true,
        )
        org.junit.Assert.assertEquals(
            "the toggle's persist used exactly one sync",
            1,
            EditorPerfProbe.syncSegmentsCalls,
        )
        advanceVirtualTime(3_400)
        org.junit.Assert.assertEquals(
            "ticks after the persist are idle again",
            1,
            EditorPerfProbe.syncSegmentsCalls,
        )
    }

    @Test
    fun perfF4_noteEndingInABlockWritesNothingOnOpenAndDone() {
        val note = runBlocking {
            repository.createRichNote(
                title = "Trailing block",
                contentHtml = "<p>Lead paragraph</p>" +
                    "<ul data-type=\"taskList\">" +
                    "<li data-type=\"taskItem\" data-checked=\"false\">" +
                    "<p>Trailing task</p></li></ul>",
            )
        }
        composeEditor(note.id)
        awaitTrue("read mode body") {
            exists("body") {
                composeRule.onNodeWithText("Lead paragraph").assertIsDisplayed()
            }
        }
        val before = runBlocking { repository.getById(note.id) }
        // Open -> pencil -> Done with NO edits: the screen's added
        // trailing text segment must be stripped by the persist
        // comparison, so nothing is written (updatedAt included).
        composeRule.onNodeWithContentDescription("Edit note").performClick()
        awaitTrue("edit mode") {
            exists("done") {
                composeRule.onNodeWithContentDescription("Done editing").assertIsDisplayed()
            }
        }
        composeRule.onNodeWithContentDescription("Done editing").performClick()
        awaitTrue("back to read mode") {
            exists("edit") {
                composeRule.onNodeWithContentDescription("Edit note").assertIsDisplayed()
            }
        }
        advanceVirtualTime(2_000)
        org.junit.Assert.assertEquals(
            "opening and closing an unchanged note writes nothing",
            before,
            runBlocking { repository.getById(note.id) },
        )
    }
}
