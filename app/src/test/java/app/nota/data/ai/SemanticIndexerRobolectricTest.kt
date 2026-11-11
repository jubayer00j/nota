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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Semantic indexer behaviour (Phase 4.2) against an in-memory
 * database and a keyword-space fake transport — the fake embeds
 * text into a tiny [apple, banana, constant] space, so "meaning"
 * in these tests is literally keyword overlap and every expected
 * hit/miss is computable by hand. No real network anywhere.
 *
 * The tests that matter most are the safety ones: a locked note
 * is NEVER embedded (its text never reaches the transport and no
 * vector is stored for it), edits replace vectors, deletes and
 * trashes remove them, sign-out drops the whole index, and a
 * provider failure degrades reconcile silently while rebuild
 * still reports it.
 */
@RunWith(RobolectricTestRunner::class)
class SemanticIndexerRobolectricTest {

    /** Gemini-shaped fake: reads the embedContent body's text and
     *  answers with the keyword-space vector for it. */
    private class KeywordEmbeddingHttpClient : AiHttpClient {
        data class Call(val url: String, val body: String)

        val calls = mutableListOf<Call>()
        var transportFailure = false

        /** Test hook fired inside every call — lets a test land
         *  a state change (a lock, an archive) in the middle of
         *  an indexing pass, between the snapshot and the embed. */
        var onCall: (suspend () -> Unit)? = null

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
            jsonBody: String,
        ): AiHttpResponse {
            calls += Call(url, jsonBody)
            onCall?.invoke()
            if (transportFailure) throw AiNetworkException(IOException("no route"))
            val body = JSONObject(jsonBody)
            if (url.contains(":batchEmbedContents")) {
                // Search Quality Pass: indexing embeds a note's
                // chunks in ONE batch call — answer like the real
                // endpoint, one embedding per request, in order.
                val requests = body.getJSONArray("requests")
                val embeddings = JSONArray()
                for (i in 0 until requests.length()) {
                    val text = requests.getJSONObject(i).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text")
                    val values = JSONArray()
                    vectorFor(text).forEach { values.put(it.toDouble()) }
                    embeddings.put(JSONObject().put("values", values))
                }
                return AiHttpResponse(
                    200,
                    JSONObject().put("embeddings", embeddings).toString(),
                )
            }
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
            File(context.cacheDir, "semantic-indexer-test-${System.nanoTime()}"),
        )
        indexer = SemanticIndexer(provider, settings, http, indexStore)
        runBlocking { settings.clearAccount(account) }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun saveGeminiKey(embeddingModel: String = "") {
        settings.saveSlot(
            account, AiProviderId.GEMINI,
            AiProviderSlot(apiKey = "test-key", embeddingModel = embeddingModel),
        )
    }

    @Test
    fun reconcileEmbedsEligibleNotesAndSearchRanksByMeaning() = runBlocking {
        saveGeminiKey()
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking season</p>")
        val banana = notes.createRichNote("Banana bread", "<p>banana banana recipe</p>")

        indexer.reconcile(account)

        val status = indexer.status(account)
        assertEquals(2, status.indexedNotes)
        assertTrue(status.spaceIsCurrent)
        assertTrue(status.canIndex)

        val appleHits = indexer.search(account, "apple")
        assertEquals(listOf(apple.id), appleHits.map { it.noteId })
        val bananaHits = indexer.search(account, "banana")
        assertEquals(listOf(banana.id), bananaHits.map { it.noteId })
    }

    @Test
    fun lockedNoteIsNeverEmbeddedAndNeverSurfaces() = runBlocking {
        saveGeminiKey()
        val open = notes.createRichNote("Orchard", "<p>apple trees everywhere</p>")
        // A locked note exactly as the lock flow leaves it in the
        // table — with a body text no one may ever read. The
        // indexer must skip it on the locked flag alone, even
        // though this row (defence-in-depth) carries plaintext.
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

        val stored = indexStore.load(account)
        assertEquals(setOf(open.id), stored?.notes?.keys)
        // The locked note's text never reached the transport.
        assertTrue(http.calls.none { it.body.contains("topsecret") })
        assertTrue(
            http.calls.none {
                it.body.contains("Secret orchard")
            },
        )
        // And it cannot surface in results.
        val hits = indexer.search(account, "apple")
        assertEquals(listOf(open.id), hits.map { it.noteId })
    }

    @Test
    fun noteLockedMidPassIsNotEmbedded() = runBlocking {
        // Jules PR #23 P1: desiredNotes() snapshots a note's
        // chunks at pass start; before the fix, a lock landing
        // while the pass was embedding an EARLIER note did not
        // stop this note being embedded from the stale snapshot —
        // its plaintext went to the provider and its vectors were
        // persisted by that pass.
        saveGeminiKey()
        val first = notes.createRichNote("First", "<p>apple one</p>")
        val second = notes.createRichNote("Second", "<p>bananasecret two</p>")
        var lockLanded = false
        http.onCall = {
            if (!lockLanded) {
                lockLanded = true
                // The lock flow's end state, landed mid-pass:
                // sealed body, empty plainText, locked flag set.
                val row = notes.getById(second.id)!!
                notes.upsert(
                    row.copy(
                        contentHtml = "sealed-envelope",
                        plainText = "",
                        locked = true,
                        lockRecordJson = "{}",
                    ),
                )
            }
        }

        indexer.reconcile(account)

        // Only the first note was embedded…
        assertEquals(setOf(first.id), indexStore.load(account)?.notes?.keys)
        // …and the second note's snapshot text never reached the
        // transport at all.
        assertTrue(http.calls.none { it.body.contains("bananasecret") })
        assertTrue(lockLanded)
    }

    @Test
    fun archivedNotesAreNotIndexedNeverSurfaceAndArePurged() = runBlocking {
        // Jules PR #23 P2: keyword (FTS) search excludes archived
        // notes everywhere; semantic search indexed and surfaced
        // them. Eligibility must agree across all three sites —
        // indexing, the search-time live filter, and (via the
        // purge) reconcile maintenance.
        saveGeminiKey()
        val active = notes.createRichNote("Active", "<p>apple active</p>")
        val archived = notes.createRichNote("Archived", "<p>apple archived</p>")
        notes.setArchived(archived, true)

        indexer.reconcile(account)

        // Never indexed…
        assertEquals(setOf(active.id), indexStore.load(account)?.notes?.keys)
        // …and never surfaces under "Related by meaning".
        assertEquals(listOf(active.id), indexer.search(account, "apple").map { it.noteId })

        // Archiving an already-indexed note purges its vectors on
        // the next pass (desiredNotes exclusion drives the purge).
        notes.setArchived(active, true)
        indexer.reconcile(account)
        assertNull(indexStore.load(account))
        assertTrue(indexer.search(account, "apple").isEmpty())
    }

    @Test
    fun searchDropsHitsWhoseTextChangedAfterEmbedding() = runBlocking {
        saveGeminiKey()
        val note = notes.createRichNote("Fruit bowl", "<p>apple apple</p>")
        indexer.reconcile(account)
        assertEquals(listOf(note.id), indexer.search(account, "apple").map { it.noteId })

        // Edit WITHOUT a reconcile: the stored vectors still
        // describe the apple text, but the note no longer does.
        // The stale hit must not surface — Ask's retrieval has
        // always validated this fingerprint; search does too
        // (Codex PR #48 R1).
        notes.updateRichBody(note, "Fruit bowl", "<p>banana banana banana</p>")
        assertTrue(indexer.search(account, "apple").none { it.noteId == note.id })

        // After the reconcile the note answers for its new text.
        indexer.reconcile(account)
        assertEquals(listOf(note.id), indexer.search(account, "banana").map { it.noteId })
    }

    @Test
    fun editReplacesTheNotesVectors() = runBlocking {
        saveGeminiKey()
        val note = notes.createRichNote("Fruit bowl", "<p>apple apple</p>")
        indexer.reconcile(account)
        val before = indexStore.load(account)!!.notes.getValue(note.id).fingerprint

        notes.updateRichBody(note, "Fruit bowl", "<p>banana banana banana</p>")
        indexer.reconcile(account)

        val after = indexStore.load(account)!!.notes.getValue(note.id).fingerprint
        assertNotEquals(before, after)
        assertEquals(listOf(note.id), indexer.search(account, "banana").map { it.noteId })
        assertTrue(indexer.search(account, "apple").none { it.noteId == note.id })
    }

    @Test
    fun trashAndDeleteRemoveVectors() = runBlocking {
        saveGeminiKey()
        val a = notes.createRichNote("A", "<p>apple</p>")
        val b = notes.createRichNote("B", "<p>banana</p>")
        indexer.reconcile(account)
        assertEquals(2, indexer.status(account).indexedNotes)

        notes.trash(a)
        indexer.reconcile(account)
        assertEquals(setOf(b.id), indexStore.load(account)?.notes?.keys)

        notes.delete(b)
        indexer.reconcile(account)
        assertEquals(0, indexer.status(account).indexedNotes)
        assertNull(indexStore.load(account))
    }

    @Test
    fun firstSearchBackfillsAnEmptyIndex() = runBlocking {
        saveGeminiKey()
        val note = notes.createRichNote("Orchard", "<p>apple apple</p>")
        // No reconcile, no rebuild — the search itself builds.
        val hits = indexer.search(account, "apple")
        assertEquals(listOf(note.id), hits.map { it.noteId })
        assertTrue(http.calls.isNotEmpty())
    }

    @Test
    fun noKeyMeansSilentNoOp() = runBlocking {
        // setUp cleared the account: no key saved anywhere.
        notes.createRichNote("Orchard", "<p>apple apple</p>")
        indexer.reconcile(account)
        assertNull(indexStore.load(account))
        assertTrue(http.calls.isEmpty())
        assertTrue(indexer.search(account, "apple").isEmpty())
        assertFalse(indexer.status(account).canIndex)
    }

    @Test
    fun embeddingModelChangeInvalidatesAndRebuildsTheSpace() = runBlocking {
        saveGeminiKey()
        val note = notes.createRichNote("Orchard", "<p>apple apple</p>")
        indexer.reconcile(account)
        assertTrue(
            indexStore.load(account)!!.signature.contains("gemini-embedding-001"),
        )

        saveGeminiKey(embeddingModel = "custom-emb-9")
        assertFalse(indexer.status(account).spaceIsCurrent)

        // The next search repairs the space under the new model…
        val hits = indexer.search(account, "apple")
        assertEquals(listOf(note.id), hits.map { it.noteId })
        assertTrue(indexStore.load(account)!!.signature.contains("custom-emb-9"))
        assertTrue(http.calls.any { it.url.contains("custom-emb-9") })
        assertTrue(indexer.status(account).spaceIsCurrent)
    }

    @Test
    fun clearAccountDropsTheWholeIndex() = runBlocking {
        saveGeminiKey()
        notes.createRichNote("Orchard", "<p>apple apple</p>")
        indexer.reconcile(account)
        assertEquals(1, indexer.status(account).indexedNotes)

        indexer.clearAccount(account)
        assertNull(indexStore.load(account))
        assertEquals(0, indexer.status(account).indexedNotes)
    }

    @Test
    fun providerFailureDegradesReconcileSilentlyButRebuildReports() = runBlocking {
        saveGeminiKey()
        notes.createRichNote("Orchard", "<p>apple apple</p>")
        http.transportFailure = true

        // Reconcile swallows it (a note save path must never see
        // an indexing failure)…
        indexer.reconcile(account)
        assertNull(indexStore.load(account))
        // …search degrades to keyword-only emptiness…
        assertTrue(indexer.search(account, "apple").isEmpty())
        // …but an explicit Rebuild surfaces the mapped failure.
        try {
            indexer.rebuild(account)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.OFFLINE, e.kind)
        }
    }

    @Test
    fun statusReflectsANewlySavedKeyWithoutAnyIndexingPass() = runBlocking {
        // The indexer half of the Settings Save wart: status()
        // reads the SAVED SETTINGS live, so the moment a key is
        // saved the status can show it — no reconcile or rebuild
        // needs to run first. (The device failure was the UI
        // refreshing its snapshot only after its save-triggered
        // reconcile finished embedding; this pins the contract
        // the fixed wiring relies on.)
        notes.createRichNote("Orchard", "<p>apple apple</p>")
        assertFalse(indexer.status(account).canIndex)

        saveGeminiKey()

        val status = indexer.status(account)
        assertTrue(status.canIndex)
        assertEquals(0, status.indexedNotes)
        assertEquals(1, status.eligibleNotes)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun rebuildKeepsServingThePreviousIndexUntilTheSwap() = runBlocking {
        // The Rebuild wart: the pre-fix build deleted the stored
        // index first, so the whole build window served nothing
        // and status read "no notes indexed yet". Build-then-swap
        // must keep the OLD index serving until the new one lands
        // whole, and publish honest progress while it runs.
        saveGeminiKey()
        val a = notes.createRichNote("A", "<p>apple orchard</p>")
        val b = notes.createRichNote("B", "<p>banana bread</p>")
        indexer.reconcile(account)
        val before = indexStore.load(account)!!
        assertEquals(setOf(a.id, b.id), before.notes.keys)

        notes.updateRichBody(a, "A", "<p>banana banana</p>")
        // Inside every embed call of the rebuild, read exactly
        // what search serves (the stored file) and what the UI
        // shows (the progress flow). status() itself takes the
        // indexer mutex the rebuild holds, so it cannot be the
        // probe here.
        val servedDuring = mutableListOf<Set<String>>()
        val progressDuring = mutableListOf<SemanticRebuildProgress?>()
        http.onCall = {
            servedDuring += indexStore.load(account)?.notes?.keys ?: emptySet()
            progressDuring += indexer.rebuildProgress.value
        }
        indexer.rebuild(account)
        http.onCall = null

        assertTrue(servedDuring.isNotEmpty())
        assertTrue(servedDuring.all { it == setOf(a.id, b.id) })
        assertTrue(progressDuring.all { it != null && it.total == 2 })
        assertEquals(
            listOf(0, 1),
            progressDuring.filterNotNull().map { it.done }.distinct(),
        )
        assertNull(indexer.rebuildProgress.value)

        // …and the swap landed whole: A's vectors were replaced.
        val after = indexStore.load(account)!!
        assertEquals(setOf(a.id, b.id), after.notes.keys)
        assertNotEquals(
            before.notes.getValue(a.id).fingerprint,
            after.notes.getValue(a.id).fingerprint,
        )
        assertTrue(indexer.search(account, "apple").none { it.noteId == a.id })
    }

    @Test
    fun failedRebuildLeavesThePreviousIndexUntouched() = runBlocking {
        saveGeminiKey()
        val note = notes.createRichNote("Orchard", "<p>apple apple</p>")
        indexer.reconcile(account)
        val before = indexStore.load(account)!!
        assertEquals(setOf(note.id), before.notes.keys)

        http.transportFailure = true
        try {
            indexer.rebuild(account)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.OFFLINE, e.kind)
        }

        // Build-then-swap: a failed build never reaches the
        // store, so the previous index is still the one serving —
        // and the progress flow has cleared.
        val after = indexStore.load(account)!!
        assertEquals(before.notes.keys, after.notes.keys)
        assertEquals(
            before.notes.getValue(note.id).fingerprint,
            after.notes.getValue(note.id).fingerprint,
        )
        assertNull(indexer.rebuildProgress.value)
        http.transportFailure = false
        assertEquals(
            listOf(note.id),
            indexer.search(account, "apple").map { it.noteId },
        )
    }
}
