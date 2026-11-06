package app.nota.feature.ask

import android.content.Context
import androidx.room.Room
import app.nota.data.ai.AiHttpClient
import app.nota.data.ai.AiHttpResponse
import app.nota.data.ai.AiNetworkException
import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AiProviderSlot
import app.nota.data.ai.AiService
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.AskCitation
import app.nota.data.ai.FakeAiKeyCipher
import app.nota.data.ai.SemanticIndexStore
import app.nota.data.ai.SemanticIndexer
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowLooper

/**
 * Ask Nota's ViewModel edges (PR #25 fix round), against an
 * in-memory database and the same dual-shape fake transport
 * as the retrieval suite:
 *
 * - A user-initiated Build surfaces a provider failure as
 *   index error copy (Codex P2) — the background reconcile's
 *   swallow-by-design must not be the Build path's behaviour.
 * - An answer is revalidated AFTER generation (Jules P1): a
 *   cited note locked during the generation call loses its
 *   citations before anything renders; losing ALL of them
 *   degrades the answer to the refusal state.
 * - The thread is keyed to the active account (Jules P2):
 *   an identity switch clears it, and the previous-turn
 *   context derived from it dies with it.
 */
@RunWith(RobolectricTestRunner::class)
class AskViewModelRobolectricTest {

    /** Gemini-shaped fake for both wire shapes Ask uses,
     *  with a URL-aware hook so a test can land a state
     *  change inside one specific call. */
    private class AskHttpClient : AiHttpClient {
        data class Call(val url: String, val body: String)

        val calls = mutableListOf<Call>()
        var chatReply: String = "Nothing to say."
        var transportFailure = false
        var onCall: (suspend (String) -> Unit)? = null

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            jsonBody: String,
        ): AiHttpResponse {
            calls += Call(url, jsonBody)
            onCall?.invoke(url)
            if (transportFailure) throw AiNetworkException(IOException("no route"))
            return when {
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
        }

        fun vectorFor(text: String): FloatArray {
            val t = text.lowercase()
            fun count(word: String) = t.split(word).size - 1
            return floatArrayOf(count("apple").toFloat(), count("banana").toFloat(), 0.5f)
        }

        val chatCalls: List<Call> get() = calls.filter { it.url.contains(":generateContent") }
    }

    private lateinit var db: NotaDatabase
    private lateinit var notes: NoteRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var http: AskHttpClient
    private lateinit var indexer: SemanticIndexer
    private lateinit var aiService: AiService

    /** The active identity the ViewModel is keyed to; the
     *  data layer underneath stays on the guest database,
     *  exactly as [DatabaseProvider.forDatabase] wires it. */
    private val accountFlow = MutableStateFlow("guest")
    private val account = "guest"

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
            SemanticIndexStore(File(context.cacheDir, "ask-vm-test-${System.nanoTime()}")),
        )
        aiService = AiService(settings, http) { accountFlow.value }
        runBlocking { settings.clearAccount(account) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun saveGeminiKey() {
        settings.saveSlot(
            account, AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "test-key"),
        )
    }

    private fun newViewModel(): AskViewModel = AskViewModel(aiService, indexer, accountFlow)

    /** Pumps the (paused) main looper until [condition] holds
     *  — the ViewModel's coroutines resume on Main while Room
     *  and the fake transport complete on real threads. */
    private fun awaitCondition(description: String, condition: () -> Boolean) {
        repeat(3_000) {
            ShadowLooper.idleMainLooper()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for: $description")
    }

    private fun settle() {
        repeat(100) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
    }

    private fun answerOf(vm: AskViewModel): AskEntry.Answer =
        vm.entries.value.filterIsInstance<AskEntry.Answer>().single()

    @Test
    fun buildIndexSurfacesAProviderFailureAsIndexError() = runBlocking {
        // Codex PR #25 P2: buildIndex rode the background
        // reconcile, which swallows provider failures by
        // design — a failed Build silently re-showed the
        // Build button and _indexError was never set.
        saveGeminiKey()
        notes.createRichNote("Orchard", "<p>apple apple</p>")
        http.transportFailure = true

        val vm = newViewModel()
        vm.buildIndex()
        awaitCondition("index error published") { vm.indexError.value != null }
        settle()
        assertFalse(vm.indexBusy.value)
        assertTrue(vm.indexError.value!!.isNotBlank())
    }

    @Test
    fun answerDegradesToRefusalWhenTheOnlyCitedNoteLocksDuringGeneration() = runBlocking {
        // Jules PR #25 P1: retrieval validated the note, then
        // the generation call ran — a lock landing inside it
        // must still keep the note out of the rendered answer.
        saveGeminiKey()
        val note = notes.createRichNote("Secret orchard", "<p>apple apple topsecret</p>")
        indexer.reconcile(account)
        http.chatReply = "The secret is topsecret [1]."

        val vm = newViewModel()
        settle()
        var lockLanded = false
        http.onCall = { url ->
            if (!lockLanded && url.contains(":generateContent")) {
                lockLanded = true
                val row = notes.getById(note.id)!!
                notes.upsert(row.copy(locked = true, lockRecordJson = "{}"))
            }
        }

        vm.ask("apple")
        awaitCondition("answer appended") {
            !vm.busy.value && vm.entries.value.size == 2
        }
        assertTrue(lockLanded)
        val answer = answerOf(vm)
        assertEquals(AskAnswerStatus.REFUSAL, answer.status)
        assertTrue(answer.citations.isEmpty())
        assertEquals("", answer.text)
    }

    @Test
    fun citationsToANoteLockedDuringGenerationAreStrippedAndTheRestSurvives() = runBlocking {
        saveGeminiKey()
        // Deterministic ranking for the question "apple
        // banana": the apple note's chunk scores a hair above
        // the banana note's, so [1] is apple and [2] is banana.
        val apple = notes.createRichNote("Apple note", "<p>apple apple</p>")
        val banana = notes.createRichNote("Banana note", "<p>banana banana banana</p>")
        indexer.reconcile(account)
        http.chatReply = "Apples ripen [1]. Bananas ripen [2]."

        val vm = newViewModel()
        settle()
        var lockLanded = false
        http.onCall = { url ->
            if (!lockLanded && url.contains(":generateContent")) {
                lockLanded = true
                val row = notes.getById(banana.id)!!
                notes.upsert(row.copy(locked = true, lockRecordJson = "{}"))
            }
        }

        vm.ask("apple banana")
        awaitCondition("answer appended") {
            !vm.busy.value && vm.entries.value.size == 2
        }
        assertTrue(lockLanded)
        val answer = answerOf(vm)
        assertEquals(AskAnswerStatus.ANSWER, answer.status)
        assertEquals(listOf(AskCitation(1, apple.id, 0)), answer.citations)
        assertTrue(answer.text.contains("[1]"))
        assertFalse(answer.text.contains("[2]"))
        // Source cards follow the surviving citations only.
        assertEquals(
            listOf(apple.id),
            sourceCardsFor(answer.sentExcerpts, answer.citations).map { it.noteId },
        )
    }

    @Test
    fun accountSwitchClearsTheThreadAndCutsThePreviousTurnContext() = runBlocking {
        // Jules PR #25 P2: the thread was only ever cleared by
        // the Clear action, so it survived an account switch —
        // on screen AND as the next prompt's previous turn.
        saveGeminiKey()
        val apple = notes.createRichNote("Apple note", "<p>apple apple</p>")
        indexer.reconcile(account)
        http.chatReply = "Apples are fruit [1]."

        val vm = newViewModel()
        settle()
        vm.ask("apple")
        awaitCondition("first answer appended") {
            !vm.busy.value && vm.entries.value.size == 2
        }
        assertEquals(AskAnswerStatus.ANSWER, answerOf(vm).status)
        // The follow-up context exists before the switch…
        assertNotNull(previousTurnOf(vm.entries.value))
        assertEquals(1, http.chatCalls.size)

        // …the identity changes…
        accountFlow.value = "uid-b"
        awaitCondition("thread cleared on switch") { vm.entries.value.isEmpty() }
        assertNull(previousTurnOf(vm.entries.value))

        // …and switching back does not resurrect anything: the
        // next ask under the original account carries NO
        // previous-turn context in its prompt.
        accountFlow.value = account
        settle()
        vm.ask("apple")
        awaitCondition("second answer appended") {
            !vm.busy.value && vm.entries.value.size == 2
        }
        assertEquals(2, http.chatCalls.size)
        assertFalse(http.chatCalls.last().body.contains("Earlier conversation"))
        assertEquals(
            listOf(AskCitation(1, apple.id, 0)),
            answerOf(vm).citations,
        )
    }
}
