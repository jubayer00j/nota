package app.nota.feature.ask

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import app.nota.data.ai.AiHttpClient
import app.nota.data.ai.AiHttpResponse
import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AiProviderSlot
import app.nota.data.ai.AiService
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.FakeAiKeyCipher
import app.nota.data.ai.SemanticIndexStore
import app.nota.data.ai.SemanticIndexer
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteRepository
import app.nota.ui.theme.NotaTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Ask Aurora (v0.26.0): the Ask surface's key states in their
 * new clothing, over the same hand-built ViewModel + dual-shape
 * fake transport as AskViewModelRobolectricTest:
 *
 *  - the NO_KEY gate renders over the aurora frame and its
 *    "Open AI settings" action still fires;
 *  - the READY empty state is the hero — headline, grounding
 *    line, the three suggestion chips — in light AND dark;
 *  - tapping a chip submits that exact question through the
 *    existing Ask flow;
 *  - an answer renders in a card with its citation source card,
 *    and tapping the source card opens the cited note.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AskAuroraUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /** Gemini-shaped fake for both wire shapes Ask uses
     *  (same vector scheme as the ViewModel suite). */
    private class AskHttpClient : AiHttpClient {
        var chatReply: String = "Nothing to say."

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            jsonBody: String,
        ): AiHttpResponse = when {
            url.contains(":embedContent") -> {
                val body = JSONObject(jsonBody)
                val text = body.getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
                val values = JSONArray()
                vectorFor(text).forEach { values.put(it.toDouble()) }
                AiHttpResponse(
                    200,
                    JSONObject()
                        .put("embedding", JSONObject().put("values", values))
                        .toString(),
                )
            }
            url.contains(":generateContent") -> AiHttpResponse(
                200,
                JSONObject()
                    .put(
                        "candidates",
                        JSONArray().put(
                            JSONObject().put(
                                "content",
                                JSONObject().put(
                                    "parts",
                                    JSONArray().put(JSONObject().put("text", chatReply)),
                                ),
                            ),
                        ),
                    )
                    .toString(),
            )
            else -> AiHttpResponse(404, "{}")
        }

        fun vectorFor(text: String): FloatArray {
            val t = text.lowercase()
            fun count(word: String) = t.split(word).size - 1
            return floatArrayOf(count("apple").toFloat(), count("banana").toFloat(), 0.5f)
        }
    }

    private lateinit var db: NotaDatabase
    private lateinit var notes: NoteRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var http: AskHttpClient
    private lateinit var indexer: SemanticIndexer

    private val accountFlow = MutableStateFlow("guest")
    private val account = "guest"

    private val openedNotes = mutableListOf<String>()
    private var settingsOpened = false

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val provider = DatabaseProvider.forDatabase(context, db)
        notes = NoteRepository(provider)
        settings = AiSettingsStore(
            PreferencesRepository(context), FakeAiKeyCipher(),
        )
        http = AskHttpClient()
        indexer = SemanticIndexer(
            provider, settings, http,
            SemanticIndexStore(File(context.cacheDir, "ask-aurora-ui-${System.nanoTime()}")),
        )
        runBlocking { settings.clearAccount(account) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun newViewModel(): AskViewModel =
        AskViewModel(AiService(settings, http) { accountFlow.value }, indexer, accountFlow)

    private fun composeAsk(vm: AskViewModel, darkTheme: Boolean = false) {
        composeRule.setContent {
            NotaTheme(darkTheme = darkTheme) {
                AskScreen(
                    onOpenNote = { openedNotes += it },
                    onOpenSettings = { settingsOpened = true },
                    viewModel = vm,
                )
            }
        }
    }

    /** Poll the main looper until [condition] holds (the
     *  SettingsScreenPolishUiTest pattern). */
    private fun awaitTrue(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (runCatching { condition() }.getOrDefault(false)) return
            Thread.sleep(50)
        }
        fail("Timed out waiting for: $description")
    }

    private fun readyViewModel(): AskViewModel = runBlocking {
        settings.saveSlot(
            account, AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "test-key"),
        )
        notes.createRichNote("Apple note", "<p>apple apple</p>")
        indexer.reconcile(account)
        newViewModel()
    }

    @Test
    fun noKeyGateShowsOverTheAuroraFrame() {
        val vm = newViewModel()
        composeAsk(vm)
        awaitTrue("gate reaches NO_KEY") { vm.gate.value == AskGate.NO_KEY }
        composeRule.onNodeWithText("Ask needs your API key").assertIsDisplayed()
        composeRule.onNodeWithText("Open AI settings").assertIsDisplayed()
        composeRule.onNodeWithText("Open AI settings").performClick()
        assertTrue(settingsOpened)
    }

    @Test
    fun heroShowsHeadlineGroundingLineAndSuggestionChips() {
        val vm = readyViewModel()
        composeAsk(vm)
        awaitTrue("gate reaches READY") { vm.gate.value == AskGate.READY }
        composeRule.onNodeWithText("Ask your notes anything.").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Answers come from your notes only — and every answer " +
                "shows the note it came from.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Summarise my newest notes").assertIsDisplayed()
        composeRule.onNodeWithText("What's due this week?").assertIsDisplayed()
        composeRule.onNodeWithText("What did I decide recently?").assertIsDisplayed()
    }

    @Test
    fun heroRendersInDarkThemeToo() {
        val vm = readyViewModel()
        composeAsk(vm, darkTheme = true)
        awaitTrue("gate reaches READY") { vm.gate.value == AskGate.READY }
        composeRule.onNodeWithText("Ask your notes anything.").assertIsDisplayed()
        composeRule.onNodeWithText("What's due this week?").assertIsDisplayed()
    }

    @Test
    fun tappingASuggestionChipSubmitsThatQuestion() {
        val vm = readyViewModel()
        composeAsk(vm)
        awaitTrue("gate reaches READY") { vm.gate.value == AskGate.READY }
        composeRule.onNodeWithText("What's due this week?").performClick()
        awaitTrue("the chip's question lands in the thread") {
            vm.entries.value.any {
                it is AskEntry.Question && it.text == "What's due this week?"
            }
        }
        composeRule.onNodeWithText("What's due this week?").assertIsDisplayed()
    }

    @Test
    fun answerCardShowsTheAnswerAndItsSourceCardOpensTheNote() {
        val appleId = runBlocking {
            settings.saveSlot(
                account, AiProviderId.GEMINI,
                AiProviderSlot(apiKey = "test-key"),
            )
            val apple = notes.createRichNote("Apple note", "<p>apple apple</p>")
            indexer.reconcile(account)
            apple.id
        }
        http.chatReply = "Apples are fruit [1]."
        val vm = newViewModel()
        composeAsk(vm)
        awaitTrue("gate reaches READY") { vm.gate.value == AskGate.READY }

        composeRule.onNode(hasSetTextAction()).performTextInput("apple")
        composeRule.onNodeWithContentDescription("Ask").performClick()
        awaitTrue("the answer lands") {
            !vm.busy.value &&
                vm.entries.value.filterIsInstance<AskEntry.Answer>()
                    .any { it.status == AskAnswerStatus.ANSWER }
        }
        composeRule.onNodeWithText("Apples are fruit", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Apple note").assertIsDisplayed()
        composeRule.onNodeWithText("Apple note").performClick()
        assertEquals(listOf(appleId), openedNotes)
    }
}
