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
import org.junit.Assert.assertNotNull
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

    /** A ~330–420-char paragraph carrying a unique marker
     *  token — long enough that under a short title each
     *  paragraph is its own chunk (the chunker packs blocks
     *  to a 500-char target), so an edit to one paragraph is
     *  an edit to exactly one chunk at a stable position. */
    private fun markedParagraph(marker: String, fruit: String, count: Int): String =
        "$fruit ".repeat(count) + marker + " " + "filler ".repeat(38)

    /** Every chunk text an embed call carried, in order —
     *  both wire shapes: :batchEmbedContents requests, and the
     *  single :embedContent call Gemini's embedAll delegates
     *  to when exactly one text is pending (the shape a
     *  one-chunk reconcile produces). */
    private fun embeddedDocumentTexts(
        calls: List<KeywordEmbeddingHttpClient.Call>,
    ): List<String> {
        val texts = mutableListOf<String>()
        for (call in calls) {
            val body = JSONObject(call.body)
            if (body.has("requests")) {
                val requests = body.getJSONArray("requests")
                for (i in 0 until requests.length()) {
                    texts += requests.getJSONObject(i).getJSONObject("content")
                        .getJSONArray("parts").getJSONObject(0).getString("text")
                }
            } else if (body.has("content")) {
                texts += body.getJSONObject("content").getJSONArray("parts")
                    .getJSONObject(0).getString("text")
            }
        }
        return texts
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
        // PR #54 R2: a completed pass that purges to empty now
        // stores an EMPTY index instead of deleting the file —
        // the purge contract itself is unchanged: the vectors
        // are GONE, and nothing surfaces below.
        assertTrue(indexStore.load(account)?.notes.isNullOrEmpty())
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
        // PR #54 R2: emptied by a completed pass ⇒ an empty
        // index is stored, not a deleted file (see above).
        assertTrue(indexStore.load(account)?.notes.isNullOrEmpty())
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
    fun reconcileEmbedsOnlyTheChangedChunkOfAMultiChunkNote() = runBlocking {
        // G10: chunking is deterministic, so a one-chunk edit
        // leaves the other chunks' texts — and therefore their
        // fingerprints — untouched. Reconcile must reuse those
        // stored vectors and send ONLY the changed chunk's text
        // to the provider.
        saveGeminiKey()
        val note = notes.createRichNote(
            "Fruit notes",
            "<p>${markedParagraph("markone", "apple", 10)}</p>" +
                "<p>${markedParagraph("marktwo", "apple", 10)}</p>" +
                "<p>${markedParagraph("markthree", "apple", 10)}</p>",
        )
        indexer.reconcile(account)
        val before = indexStore.load(account)!!.notes.getValue(note.id)
        assertEquals(3, before.chunks.size)
        http.calls.clear()

        notes.updateRichBody(
            note, "Fruit notes",
            "<p>${markedParagraph("markone", "apple", 10)}</p>" +
                "<p>${markedParagraph("marktwoCHANGED", "apple", 10)}</p>" +
                "<p>${markedParagraph("markthree", "apple", 10)}</p>",
        )
        indexer.reconcile(account)

        // Exactly one chunk text crossed the wire: the changed
        // middle chunk's.
        val embedded = embeddedDocumentTexts(http.calls)
        assertEquals(1, embedded.size)
        assertTrue(embedded.single().contains("marktwoCHANGED"))

        val after = indexStore.load(account)!!.notes.getValue(note.id)
        assertEquals(3, after.chunks.size)
        // Untouched chunks kept their fingerprints AND vectors…
        assertEquals(before.chunks[0].fingerprint, after.chunks[0].fingerprint)
        assertEquals(before.chunks[2].fingerprint, after.chunks[2].fingerprint)
        assertTrue(before.chunks[0].vector.contentEquals(after.chunks[0].vector))
        assertTrue(before.chunks[2].vector.contentEquals(after.chunks[2].vector))
        // …the changed chunk got a fresh fingerprint, and the
        // note-level fingerprint moved as it always has.
        assertNotEquals(before.chunks[1].fingerprint, after.chunks[1].fingerprint)
        assertNotEquals(before.fingerprint, after.fingerprint)
    }

    @Test
    fun searchServesANoteThroughAnUnchangedChunkAfterASiblingEdit() = runBlocking {
        // G10: freshness is per chunk. The banana passage lives
        // in chunk 0; editing chunk 1 (apple → zebra) without a
        // reconcile must NOT silence the note for "banana" —
        // chunk 0's stored vector still answers for chunk 0's
        // current text — while the stale apple vector of the
        // changed chunk must stop answering for "apple".
        saveGeminiKey()
        val note = notes.createRichNote(
            "Fruit notes",
            "<p>${markedParagraph("bananapatch", "banana", 20)}</p>" +
                "<p>${markedParagraph("applepatch", "apple", 10)}</p>",
        )
        indexer.reconcile(account)
        assertEquals(listOf(note.id), indexer.search(account, "banana").map { it.noteId })
        assertEquals(listOf(note.id), indexer.search(account, "apple").map { it.noteId })

        notes.updateRichBody(
            note, "Fruit notes",
            "<p>${markedParagraph("bananapatch", "banana", 20)}</p>" +
                "<p>${markedParagraph("zebrapatch", "zebra", 10)}</p>",
        )

        // No reconcile: the unchanged chunk still serves…
        assertEquals(listOf(note.id), indexer.search(account, "banana").map { it.noteId })
        // …and the changed chunk's stale vector serves nothing.
        assertTrue(indexer.search(account, "apple").none { it.noteId == note.id })
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

    @Test
    fun revalidateAskCitationsValidatesPerChunkAgainstTheSentText() = runBlocking {
        // PR #49 fix round (Codex R1): the post-generation
        // gate validates each citation against the excerpt
        // text retrieval actually sent — a sibling chunk's
        // edit cannot kill it, and an edit to the cited
        // chunk itself still does.
        saveGeminiKey()
        val note = notes.createRichNote(
            "Fruit notes",
            "<p>${markedParagraph("bananapatch", "banana", 20)}</p>" +
                "<p>${markedParagraph("applepatch", "apple", 10)}</p>",
        )
        indexer.reconcile(account)
        val chunks = NoteChunker.chunk(note.title, note.plainText)
        assertEquals(2, chunks.size)
        val sent = chunks.mapIndexed { index, text ->
            AskExcerpt(note.id, index, note.title, text, 0.9)
        }
        val citations = listOf(
            AskCitation(1, note.id, 0),
            AskCitation(2, note.id, 1),
        )
        // Nothing edited: both citations validate.
        assertEquals(
            setOf(1, 2),
            indexer.revalidateAskCitations(account, sent, citations),
        )
        // Edit chunk 1 only: citation 2 dies; citation 1 —
        // to a different chunk of the same note — survives.
        notes.updateRichBody(
            note, "Fruit notes",
            "<p>${markedParagraph("bananapatch", "banana", 20)}</p>" +
                "<p>${markedParagraph("zebrapatch", "zebra", 10)}</p>",
        )
        assertEquals(
            setOf(1),
            indexer.revalidateAskCitations(account, sent, citations),
        )
    }

    // ---------------------------------------------------------------
    // Performance chapter 2 (F3/F8): the stamp gate and the
    // whole-note fingerprint fast paths. CI asserts COUNTS, never
    // wall-clock: provider calls and NoteChunker invocations.
    // ---------------------------------------------------------------

    @Test
    fun unchangedLibrarySecondReconcileDoesNoChunkingAndNoProviderCalls() = runBlocking {
        saveGeminiKey()
        notes.createRichNote("Apple harvest", "<p>apple apple picking season</p>")
        notes.createRichNote("Banana bread", "<p>banana banana recipe</p>")
        indexer.reconcile(account)
        assertEquals(2, indexer.status(account).indexedNotes)

        http.calls.clear()
        val chunksBefore = NoteChunker.chunkInvocations
        indexer.reconcile(account)

        // The stamp gate fired: the pass returned before any
        // chunking and before any provider call, index intact.
        assertTrue(http.calls.isEmpty())
        assertEquals(chunksBefore, NoteChunker.chunkInvocations)
        assertEquals(2, indexer.status(account).indexedNotes)
    }

    @Test
    fun lockingOneNotePurgesExactlyItsVectorsAndEmbedsNothingElse() = runBlocking {
        saveGeminiKey()
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking season</p>")
        val banana = notes.createRichNote("Banana bread", "<p>banana banana recipe</p>")
        indexer.reconcile(account)
        assertEquals(2, indexer.status(account).indexedNotes)

        // Lock the banana note the way the lock flow leaves it in
        // the table: sealed body, empty projection, locked flag.
        val bananaRow = db.noteDao().getById(banana.id)!!
        db.noteDao().upsert(bananaRow.copy(locked = true, contentHtml = "", plainText = ""))

        http.calls.clear()
        indexer.reconcile(account)

        // The purge is exact and local: banana's vectors are gone,
        // apple's remain, and nothing was embedded on the way.
        assertTrue(http.calls.isEmpty())
        assertEquals(1, indexer.status(account).indexedNotes)
        assertEquals(listOf(apple.id), indexer.search(account, "apple").map { it.noteId })
        assertTrue(indexer.search(account, "banana").isEmpty())
    }

    @Test
    fun searchAndAskOnUnchangedLibraryTakeTheFingerprintFastPath() = runBlocking {
        saveGeminiKey()
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking season</p>")
        notes.createRichNote("Banana bread", "<p>banana banana recipe</p>")
        indexer.reconcile(account)

        // Search: unchanged notes align their stored vectors by
        // fingerprint — zero chunker invocations.
        val chunksBefore = NoteChunker.chunkInvocations
        val hits = indexer.search(account, "apple")
        assertEquals(listOf(apple.id), hits.map { it.noteId })
        assertEquals(chunksBefore, NoteChunker.chunkInvocations)

        // Ask retrieval: the pre-embed validity pass chunks
        // nothing for unchanged notes; the post-embed pass chunks
        // exactly once per candidate note, solely to re-derive
        // the excerpt texts (which are stored nowhere).
        val excerpts = indexer.retrieveAskExcerpts(account, "apple picking")
        assertTrue(excerpts.isNotEmpty())
        assertEquals(chunksBefore + 2, NoteChunker.chunkInvocations)

        // Citation revalidation on unchanged notes: zero chunker
        // invocations, every citation still valid.
        val citationChunks = NoteChunker.chunkInvocations
        val valid = indexer.revalidateAskCitations(
            account,
            excerpts,
            excerpts.mapIndexed { i, e -> AskCitation(i + 1, e.noteId, e.chunkIndex) },
        )
        assertEquals(excerpts.size, valid.size)
        assertEquals(citationChunks, NoteChunker.chunkInvocations)
    }

    // ---------------------------------------------------------------
    // PR #54 Codex R1: the no-config reconcile path persists its
    // purge but must NOT advance the library stamp — the stamp
    // records the last COMPLETED (embedding) pass. A library that
    // changed while unconfigured must still be embedded by the
    // next configured reconcile, not gated out as "unchanged".
    // ---------------------------------------------------------------

    @Test
    fun unconfiguredReconcilePurgesWithoutStamping_thenConfiguredReconcileEmbedsTheChanges() = runBlocking {
        saveGeminiKey()
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking season</p>")
        val banana = notes.createRichNote("Banana bread", "<p>banana banana recipe</p>")
        indexer.reconcile(account)
        assertEquals(2, indexer.status(account).indexedNotes)
        val stampAfterIndexing = indexStore.loadStamp(account)
        assertNotNull(stampAfterIndexing)

        // The key goes away; while unconfigured the library
        // changes twice: apple's body is edited, banana is locked
        // (the lock flow's table shape: sealed body, empty
        // projection, locked flag — as in the purge pin above).
        settings.clearAccount(account)
        notes.updateRichBody(apple, "Apple harvest", "<p>banana banana orchard edit</p>")
        val bananaRow = db.noteDao().getById(banana.id)!!
        db.noteDao().upsert(bananaRow.copy(locked = true, contentHtml = "", plainText = ""))

        http.calls.clear()
        indexer.reconcile(account)

        // The purge half still ran, locally: banana's vectors are
        // gone, nothing was embedded — and the stamp did NOT move,
        // so this pass is not recorded as a completed one.
        assertTrue(http.calls.isEmpty())
        assertEquals(setOf(apple.id), indexStore.load(account)?.notes?.keys)
        assertEquals(stampAfterIndexing, indexStore.loadStamp(account))

        // The key returns: the gate must not skip — apple's edit,
        // made while unconfigured, is embedded now, and banana
        // stays purged.
        saveGeminiKey()
        indexer.reconcile(account)
        assertTrue(http.calls.isNotEmpty())
        assertTrue(
            embeddedDocumentTexts(http.calls).any { it.contains("orchard edit") },
        )
        assertEquals(setOf(apple.id), indexStore.load(account)?.notes?.keys)
        assertEquals(
            listOf(apple.id),
            indexer.search(account, "banana").map { it.noteId },
        )
    }

    // ---------------------------------------------------------------
    // PR #54 Codex R2: a completed CONFIGURED pass that leaves
    // nothing indexed stores an EMPTY index (active signature,
    // zero notes) instead of deleting the file. persist() deletes
    // on an empty working set, and a missing index can never
    // satisfy the stamp gate's stored-index check — so an empty
    // library (or one purged to empty) re-ran the full candidate
    // scan on every reconcile despite an unchanged stamp. Search
    // and Ask treat an empty index exactly like a missing one
    // (both check notes.isEmpty()), so no answer changes.
    // ---------------------------------------------------------------

    @Test
    fun emptyLibraryReconcileStampsAndGates() = runBlocking {
        saveGeminiKey()
        // A library with zero indexable notes: its only note is
        // blank (blank title, empty body — the chunker's blank
        // rule keeps it out of the desired set).
        notes.createRichNote("", "<p></p>")
        indexer.reconcile(account)

        // The completed pass left an empty index behind. This
        // load reads the on-disk file directly (not the indexer
        // cache), so it also proves the store's format round-
        // trips a zero-note index: non-null, the active space's
        // signature intact, zero notes. The stamp landed too.
        val stored = indexStore.load(account)
        assertNotNull(stored)
        assertTrue(stored!!.notes.isEmpty())
        assertTrue(stored.signature.contains("gemini-embedding-001"))
        assertNotNull(indexStore.loadStamp(account))
        assertEquals(0, indexer.status(account).indexedNotes)

        http.calls.clear()
        val chunksBefore = NoteChunker.chunkInvocations
        indexer.reconcile(account)

        // The gate hit on the unchanged library: the pass
        // returned before any chunking and any provider call.
        assertTrue(http.calls.isEmpty())
        assertEquals(chunksBefore, NoteChunker.chunkInvocations)
    }

    @Test
    fun purgeToEmptyStillGates() = runBlocking {
        saveGeminiKey()
        val apple = notes.createRichNote("Apple harvest", "<p>apple apple picking season</p>")
        indexer.reconcile(account)
        assertEquals(1, indexer.status(account).indexedNotes)

        // Lock the only indexed note (the lock flow's table
        // shape, as in the purge pins above): the next reconcile
        // purges it and the working set empties.
        val row = db.noteDao().getById(apple.id)!!
        db.noteDao().upsert(row.copy(locked = true, contentHtml = "", plainText = ""))
        indexer.reconcile(account)

        // The purge left an empty index stored — not a deleted
        // file — so the gate survives the purge.
        assertEquals(0, indexer.status(account).indexedNotes)
        val stored = indexStore.load(account)
        assertNotNull(stored)
        assertTrue(stored!!.notes.isEmpty())
        assertTrue(indexer.search(account, "apple").isEmpty())

        http.calls.clear()
        val chunksBefore = NoteChunker.chunkInvocations
        indexer.reconcile(account)

        // Still gates: zero chunker invocations, zero provider
        // calls on the unchanged, now-empty library.
        assertTrue(http.calls.isEmpty())
        assertEquals(chunksBefore, NoteChunker.chunkInvocations)
    }

    @Test
    fun emptyIndexDoesNotGateAForeignSpace() = runBlocking {
        saveGeminiKey()
        val note = notes.createRichNote("Apple harvest", "<p>apple apple picking season</p>")

        // Arrange the state a completed pass over an empty
        // library leaves under space A — an empty index signed
        // with A's signature — together with the stamp of the
        // CURRENT library, so the only thing that can keep the
        // gate from firing after the space switch is the
        // signature check itself.
        val configA = settings.activeEmbeddingConfig(account)!!
        indexStore.save(
            account,
            StoredSemanticIndex(configA.spaceSignature(), 0, emptyMap()),
        )
        indexStore.saveStamp(account, db.noteDao().getLibraryStampRow())

        // Control: under space A the gate hits — nothing runs.
        indexer.reconcile(account)
        assertTrue(http.calls.isEmpty())

        // Switch the embedding space; the library and its stamp
        // are untouched, but the stored empty index belongs to A.
        saveGeminiKey(embeddingModel = "custom-emb-9")
        indexer.reconcile(account)

        // No gate hit: the full pass ran under B and embedded
        // the note through the provider.
        assertTrue(http.calls.any { it.url.contains("custom-emb-9") })
        val stored = indexStore.load(account)
        assertEquals(setOf(note.id), stored?.notes?.keys)
        assertTrue(stored!!.signature.contains("custom-emb-9"))
    }
}
