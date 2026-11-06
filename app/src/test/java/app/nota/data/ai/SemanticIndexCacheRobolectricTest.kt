package app.nota.data.ai

import android.content.Context
import androidx.room.Room
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.NoteRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Phase 6.1: the SemanticIndexer's in-memory index cache. Before this
 * round every search keystroke, Ask question and status check
 * re-read and re-deserialised the whole vector file from disk; now
 * the file is the persistence copy and the cache is the read path.
 * These tests pin the coherence contract that makes that safe:
 * reads serve from memory, and every mutation path (reconcile,
 * evict, clear) refreshes the cache in the same step — a stale
 * vector must never outlive the state that invalidated it, above
 * all a locked note's.
 */
@RunWith(RobolectricTestRunner::class)
class SemanticIndexCacheRobolectricTest {

    /** Same keyword-space fake as SemanticIndexerRobolectricTest:
     *  text embeds into [apple, banana, constant] space. */
    private class KeywordEmbeddingHttpClient : AiHttpClient {
        var transportFailure = false

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            jsonBody: String,
        ): AiHttpResponse {
            if (transportFailure) throw AiNetworkException(IOException("no route"))
            val body = JSONObject(jsonBody)
            val text = body.getJSONObject("content").getJSONArray("parts")
                .getJSONObject(0).getString("text")
            val values = JSONArray()
            vectorFor(text).forEach { values.put(it.toDouble()) }
            return AiHttpResponse(
                200,
                JSONObject().put("embedding", JSONObject().put("values", values)).toString(),
            )
        }

        fun vectorFor(text: String): FloatArray {
            val t = text.lowercase()
            fun count(word: String) = t.split(word).size - 1
            return floatArrayOf(count("apple").toFloat(), count("banana").toFloat(), 0.5f)
        }
    }

    private lateinit var db: NotaDatabase
    private lateinit var provider: DatabaseProvider
    private lateinit var notes: NoteRepository
    private lateinit var settings: AiSettingsStore
    private lateinit var http: KeywordEmbeddingHttpClient
    private lateinit var indexStore: SemanticIndexStore
    private lateinit var indexer: SemanticIndexer

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
        http = KeywordEmbeddingHttpClient()
        indexStore = SemanticIndexStore(
            File(context.cacheDir, "semantic-cache-test-${System.nanoTime()}"),
        )
        indexer = SemanticIndexer(provider, settings, http, indexStore)
        runBlocking {
            settings.clearAccount(account)
            settings.saveSlot(
                account, AiProviderId.GEMINI,
                AiProviderSlot(apiKey = "test-key", embeddingModel = ""),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun searchServesFromCacheAfterTheFileDisappears() = runBlocking {
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking</p>")
        indexer.reconcile(account)
        assertEquals(listOf(apple.id), indexer.search(account, "apple").map { it.noteId })

        // The file is gone (deleted behind the indexer's back): the
        // in-memory cache is now the only copy, and search must keep
        // serving from it — that IS the read-path change. (Production
        // deletions flow through the indexer itself; nothing else
        // removes the file under a live process.)
        indexStore.delete(account)
        assertEquals(listOf(apple.id), indexer.search(account, "apple").map { it.noteId })
    }

    @Test
    fun evictNoteDropsItFromTheCacheImmediately() = runBlocking {
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking</p>")
        val banana = notes.createRichNote("Banana bread", "<p>banana banana recipe</p>")
        indexer.reconcile(account)
        assertEquals(listOf(apple.id), indexer.search(account, "apple").map { it.noteId })

        // The lock flow's eviction: the note's vectors leave the
        // cache in the same call, not at the next disk read.
        indexer.evictNote(account, apple.id)
        assertTrue(indexer.search(account, "apple").isEmpty())
        assertEquals(listOf(banana.id), indexer.search(account, "banana").map { it.noteId })
        // And the persistence copy agrees.
        assertEquals(setOf(banana.id), indexStore.load(account)?.notes?.keys)
    }

    @Test
    fun clearAccountDropsTheCacheToo() = runBlocking {
        notes.createRichNote("Apple harvest", "<p>apple apple picking</p>")
        indexer.reconcile(account)
        assertEquals(1, indexer.status(account).indexedNotes)

        indexer.clearAccount(account)
        // Status reads the cache: a dropped index reports zero, and
        // the file is gone as well (sign-out leaves nothing behind).
        assertEquals(0, indexer.status(account).indexedNotes)
        assertNull(indexStore.load(account))
    }

    @Test
    fun reconcileRefreshesTheCacheWhenNotesChange() = runBlocking {
        val note = notes.createRichNote("Orchard", "<p>apple trees</p>")
        indexer.reconcile(account)
        assertEquals(listOf(note.id), indexer.search(account, "apple").map { it.noteId })

        // Edit the note's text away from "apple": the next reconcile
        // re-embeds it, and search over the CACHE must reflect the
        // new vectors, not the ones the first pass cached.
        val edited = notes.getById(note.id)!!
        notes.updateRichBody(edited, "Orchard", "<p>banana banana banana</p>")
        indexer.reconcile(account)
        assertTrue(indexer.search(account, "apple").isEmpty())
        assertEquals(listOf(note.id), indexer.search(account, "banana").map { it.noteId })
    }
}
