package app.nota.data.ai

import android.content.Context
import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NoteEntity
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ask Nota end to end (Phase 4.3) against an in-memory
 * database and a dual-shape fake transport: the same
 * keyword-space embeddings as the semantic suite (meaning =
 * apple/banana overlap, every score computable by hand), plus
 * a canned generateContent reply. No real network anywhere.
 *
 * The tests that matter are the grounding ones: nothing
 * reaches the chat call unless it cleared the floor AND
 * fresh-passage validation at answer time — a note locked,
 * archived, trashed or edited after indexing contributes no
 * passage and no citation — and with no passages there is no
 * chat call at all.
 */
@RunWith(RobolectricTestRunner::class)
class AskNotaRobolectricTest {

    /** Gemini-shaped fake for both wire shapes Ask uses. */
    private class AskHttpClient : AiHttpClient {
        data class Call(val url: String, val body: String)

        val calls = mutableListOf<Call>()
        var chatReply: String = ASK_REFUSAL_SENTENCE
        var transportFailure = false

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            jsonBody: String,
        ): AiHttpResponse {
            calls += Call(url, jsonBody)
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
    private lateinit var provider: DatabaseProvider
    private lateinit var notes: NoteRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var http: AskHttpClient
    private lateinit var indexStore: SemanticIndexStore
    private lateinit var indexer: SemanticIndexer
    private lateinit var aiService: AiService

    private val account = "guest"

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, NotaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        provider = DatabaseProvider.forDatabase(context, db)
        notes = NoteRepository(provider)
        settings = AiSettingsStore(PreferencesRepository(context), FakeAiKeyCipher())
        http = AskHttpClient()
        indexStore = SemanticIndexStore(
            File(context.cacheDir, "ask-nota-test-${System.nanoTime()}"),
        )
        indexer = SemanticIndexer(provider, settings, http, indexStore)
        aiService = AiService(settings, http) { account }
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

    /** A ~295-char paragraph mentioning "apple" [apples] times
     *  — long enough to chunk on its own, filler otherwise. */
    private fun paragraph(apples: Int): String =
        "apple ".repeat(apples) + "filler ".repeat(38)

    @Test
    fun askAnswersFromTheNotesWithValidatedCitations() = runBlocking {
        saveGeminiKey()
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking season</p>")
        notes.createRichNote("Banana bread", "<p>banana banana recipe</p>")
        indexer.reconcile(account)
        http.chatReply = "You pick the apples in season [1]."

        val excerpts = indexer.retrieveAskExcerpts(account, "apple")
        assertEquals(listOf(apple.id), excerpts.map { it.noteId })

        val result = aiService.ask("apple", excerpts)
        assertEquals(
            AskAnswerResult.Answer(
                text = "You pick the apples in season [1].",
                citations = listOf(AskCitation(1, apple.id, 0)),
            ),
            result,
        )
        assertEquals(1, http.chatCalls.size)
        val prompt = http.chatCalls.single().body
        assertTrue(prompt.contains("You are Ask Nota"))
        assertTrue(prompt.contains("[1] From \"Apple harvest\""))
        // The banana note scored below the floor: it was never sent.
        assertFalse(prompt.contains("Banana bread"))
    }

    @Test
    fun noPassageAboveTheFloorMeansNoEvidenceAndNoChatCall() = runBlocking {
        saveGeminiKey()
        notes.createRichNote("Zebra", "<p>zebra stripes at the zoo</p>")
        indexer.reconcile(account)

        val excerpts = indexer.retrieveAskExcerpts(account, "apple")
        assertTrue(excerpts.isEmpty())
        assertEquals(AskAnswerResult.NoEvidence, aiService.ask("apple", excerpts))
        assertTrue(http.chatCalls.isEmpty())
    }

    @Test
    fun serviceAskWithNoExcerptsMakesNoProviderCallAtAll() = runBlocking {
        saveGeminiKey()
        assertEquals(
            AskAnswerResult.NoEvidence,
            aiService.ask("anything at all", emptyList()),
        )
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun lockedAfterIndexingNeverReachesThePromptOrTheCitations() = runBlocking {
        saveGeminiKey()
        val open = notes.createRichNote("Orchard", "<p>apple trees</p>")
        val secret = notes.createRichNote("Secret orchard", "<p>apple apple topsecret</p>")
        indexer.reconcile(account)
        assertEquals(2, indexer.status(account).indexedNotes)

        // The flag-only lock: title and plaintext stay exactly
        // as indexed, so the fingerprint still matches and ONLY
        // the eligibility re-check can keep this note out.
        val row = notes.getById(secret.id)!!
        notes.upsert(row.copy(locked = true, lockRecordJson = "{}"))

        val excerpts = indexer.retrieveAskExcerpts(account, "apple")
        assertEquals(listOf(open.id), excerpts.map { it.noteId })

        http.chatReply = "The open orchard has apple trees [1]."
        val result = aiService.ask("apple", excerpts)
        assertTrue(result is AskAnswerResult.Answer)
        assertEquals(
            listOf(AskCitation(1, open.id, 0)),
            (result as AskAnswerResult.Answer).citations,
        )
        val prompt = http.chatCalls.single().body
        assertFalse(prompt.contains("topsecret"))
        assertFalse(prompt.contains("Secret orchard"))
    }

    @Test
    fun archivedAndTrashedNotesAreExcludedAtAnswerTime() = runBlocking {
        saveGeminiKey()
        val a = notes.createRichNote("A", "<p>apple one</p>")
        val b = notes.createRichNote("B", "<p>apple two</p>")
        indexer.reconcile(account)

        notes.setArchived(a, true)
        assertEquals(
            listOf(b.id),
            indexer.retrieveAskExcerpts(account, "apple").map { it.noteId },
        )

        notes.trash(b)
        assertTrue(indexer.retrieveAskExcerpts(account, "apple").isEmpty())
        assertEquals(
            AskAnswerResult.NoEvidence,
            aiService.ask("apple", indexer.retrieveAskExcerpts(account, "apple")),
        )
        assertTrue(http.chatCalls.isEmpty())
    }

    @Test
    fun editedNoteContributesNothingUntilItIsReindexed() = runBlocking {
        saveGeminiKey()
        val note = notes.createRichNote("Fruit bowl", "<p>apple apple</p>")
        indexer.reconcile(account)
        assertEquals(1, indexer.retrieveAskExcerpts(account, "apple").size)

        // The edit lands after indexing: the stored vectors now
        // describe text the note no longer holds. Neither the
        // old meaning nor the new one may be quoted from them.
        notes.updateRichBody(note, "Fruit bowl", "<p>banana banana</p>")
        assertTrue(indexer.retrieveAskExcerpts(account, "apple").isEmpty())
        assertTrue(indexer.retrieveAskExcerpts(account, "banana").isEmpty())

        // After the maintenance pass re-embeds, the note answers
        // again — with its CURRENT text.
        indexer.reconcile(account)
        val excerpts = indexer.retrieveAskExcerpts(account, "banana")
        assertEquals(listOf(note.id), excerpts.map { it.noteId })
        assertTrue(excerpts.single().text.contains("banana"))
    }

    @Test
    fun retrievalCapsAtSixExcerptsAndTwoPerNote() = runBlocking {
        saveGeminiKey()
        val created = (1..4).map { i ->
            notes.createRichNote(
                "Note $i",
                "<p>${paragraph(20)}</p><p>${paragraph(20)}</p><p>${paragraph(20)}</p>",
            )
        }
        indexer.reconcile(account)

        val excerpts = indexer.retrieveAskExcerpts(account, "apple")
        assertEquals(ASK_MAX_EXCERPTS, excerpts.size)
        val perNote = excerpts.groupingBy { it.noteId }.eachCount()
        assertTrue(perNote.values.all { it <= ASK_MAX_EXCERPTS_PER_NOTE })
        assertEquals(3, perNote.size)
        assertTrue(perNote.keys.all { id -> created.any { it.id == id } })
    }

    @Test
    fun perNoteCapKeepsTheBestTwoChunksNotTheFirstTwo() = runBlocking {
        saveGeminiKey()
        // Chunk densities 5, 1, 3 apples: against the "apple"
        // query vector the cosines rank chunk 1 (density 1 —
        // the query's own direction) above chunk 2 (3) above
        // chunk 0 (5). The cap must keep the best two: 1 and 2.
        val note = notes.createRichNote(
            "Fruit bowl",
            "<p>${paragraph(5)}</p><p>${paragraph(1)}</p><p>${paragraph(3)}</p>",
        )
        indexer.reconcile(account)

        val excerpts = indexer.retrieveAskExcerpts(account, "apple")
        assertEquals(listOf(note.id, note.id), excerpts.map { it.noteId })
        assertEquals(listOf(1, 2), excerpts.map { it.chunkIndex })
    }

    @Test
    fun hallucinatedCitationsAreDroppedFromTheAnswer() = runBlocking {
        saveGeminiKey()
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking</p>")
        indexer.reconcile(account)
        http.chatReply = "Apples are picked [1], and pears too [7]."

        val result = aiService.ask("apple", indexer.retrieveAskExcerpts(account, "apple"))
        assertTrue(result is AskAnswerResult.Answer)
        val answer = result as AskAnswerResult.Answer
        assertEquals(listOf(AskCitation(1, apple.id, 0)), answer.citations)
        assertFalse(answer.text.contains("[7]"))
    }

    @Test
    fun theModelsRefusalSentenceParsesAsARefusal() = runBlocking {
        saveGeminiKey()
        notes.createRichNote("Apple harvest", "<p>apple apple picking</p>")
        indexer.reconcile(account)
        http.chatReply = ASK_REFUSAL_SENTENCE

        assertEquals(
            AskAnswerResult.Refusal,
            aiService.ask("apple", indexer.retrieveAskExcerpts(account, "apple")),
        )
    }

    @Test
    fun bengaliDigitCitationSurvivesTheRoundTrip() = runBlocking {
        saveGeminiKey()
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking</p>")
        indexer.reconcile(account)
        http.chatReply = "আপেল তোলা হয় [১]।"

        val result = aiService.ask("apple", indexer.retrieveAskExcerpts(account, "apple"))
        assertTrue(result is AskAnswerResult.Answer)
        val answer = result as AskAnswerResult.Answer
        assertEquals(listOf(AskCitation(1, apple.id, 0)), answer.citations)
        assertTrue(answer.text.contains("[1]"))
        assertFalse(answer.text.contains("[১]"))
    }

    @Test
    fun questionEmbeddingFailurePropagatesInsteadOfPosingAsNoEvidence() = runBlocking {
        saveGeminiKey()
        notes.createRichNote("Orchard", "<p>apple apple</p>")
        indexer.reconcile(account)

        http.transportFailure = true
        try {
            indexer.retrieveAskExcerpts(account, "apple")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.OFFLINE, e.kind)
        }
    }

    @Test
    fun noKeyMeansRetrievalIsEmptyAndAskThrowsNoKey() = runBlocking {
        // setUp cleared the account: no key saved anywhere.
        notes.createRichNote("Orchard", "<p>apple apple</p>")
        assertTrue(indexer.retrieveAskExcerpts(account, "apple").isEmpty())
        assertTrue(http.calls.isEmpty())
        try {
            aiService.ask(
                "apple",
                listOf(AskExcerpt("x", 0, "T", "apple text", 0.9)),
            )
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NO_KEY, e.kind)
        }
    }

    @Test
    fun lockedRowSeededDirectlyIsNeverIndexedSoNeverAskedAbout() = runBlocking {
        // Defence in depth, mirroring the semantic suite: a
        // locked row carrying plaintext (as a stale row could)
        // is skipped by indexing itself, so Ask never sees it.
        saveGeminiKey()
        val locked = NoteEntity(
            id = UUID.randomUUID().toString(),
            title = "Secret orchard",
            contentHtml = "sealed-envelope",
            plainText = "apple apple apple topsecret",
            createdAt = 1L,
            updatedAt = 1L,
            locked = true,
            lockRecordJson = "{}",
        )
        notes.upsert(locked)
        indexer.reconcile(account)
        assertTrue(indexer.retrieveAskExcerpts(account, "apple").isEmpty())
        assertTrue(http.calls.none { it.body.contains("topsecret") })
    }
}
