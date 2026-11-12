package app.nota.data.ai

import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NoteEntity
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Settings shows about the index: how many notes carry
 *  vectors, how many could, and whether the stored vectors belong
 *  to the current provider/embedding-model space at all. */
data class SemanticIndexStatus(
    val indexedNotes: Int,
    val eligibleNotes: Int,
    val canIndex: Boolean,
    val spaceIsCurrent: Boolean,
)

/** A running full rebuild's progress, published by
 *  [SemanticIndexer] so Settings can show an honest count while
 *  it runs: [done] of the [total] eligible notes the pass set out
 *  to embed have been processed. Counts the pass itself — never
 *  an estimate, never a timer. */
data class SemanticRebuildProgress(
    val accountId: String,
    val done: Int,
    val total: Int,
)

/**
 * Semantic search's engine room (Phase 4.2): owns the lifecycle
 * of the per-account vector index — build, reconcile, query,
 * drop — over [SemanticIndexStore].
 *
 * The rules, carried from 1.x #8.5 and hardened for the native
 * per-note lock:
 *
 * - **Locked notes are NEVER indexed.** Eligibility is
 *   `!locked && !trashed && !archived` and the reconcile purge
 *   enforces it locally, with no provider involved: a note that
 *   becomes locked, trashed, archived, emptied or deleted loses
 *   its vectors in the same pass that would otherwise embed it,
 *   so a plaintext-derived vector never outlives the state that
 *   made its note unembeddable. Search results are re-checked
 *   against the live note rows before surfacing, as a second
 *   line of defence. Two further guards close the windows a
 *   single snapshot cannot see (Jules review of PR #23): every
 *   embed re-checks the note's LIVE row first, so a lock that
 *   lands mid-pass is honoured by the pass already running; and
 *   the lock flow evicts a note's vectors the moment it locks
 *   ([evictNote]) instead of waiting for the next pass.
 * - **One embedding space per index.** Every stored vector was
 *   written under the config's space signature; a provider,
 *   endpoint or embedding-model change makes the stored index
 *   foreign, and foreign vectors are dropped, never compared.
 * - **Best-effort everywhere.** Reconcile never throws — a
 *   provider failure leaves the previous index in place and the
 *   next trigger (a note save's debounce, a Settings save, a
 *   search) retries. Indexing never blocks or fails a note save.
 */
class SemanticIndexer(
    private val databaseProvider: DatabaseProvider,
    private val settings: AiSettingsStore,
    private val http: AiHttpClient,
    private val store: SemanticIndexStore,
) {
    private val mutex = Mutex()

    // Phase 6.1: the loaded index, cached in memory. Every read used
    // to re-read and re-deserialise the whole vector file from disk —
    // per debounced search keystroke, per Ask question, per status
    // check. Every write to the store in the app flows through this
    // class (rebuild, reconcile, persist, evict, clear — verified by
    // grep over SemanticIndexStore call sites): [storeIndex] is the
    // ONLY way anything mutates the store from here, and it refreshes
    // the cache with a snapshot copy in the same step. The file
    // remains the persistence copy (survives process death); the
    // cache is the read path. Coherence across the one unlocked
    // step — the cold disk read in [loadIndex] — is the slot's
    // generation discipline (see SemanticIndexCacheSlot): a store
    // landing mid-load is never overwritten by the stale load.
    private val cacheSlot = SemanticIndexCacheSlot()

    /** The account's index: memory first, disk on a cold cache. */
    private fun loadIndex(accountId: String): StoredSemanticIndex? {
        val lookup = cacheSlot.get(accountId)
        if (lookup.hit) return lookup.index
        val loaded = store.load(accountId)
        return cacheSlot.fill(accountId, loaded, lookup.generation)
    }

    /** The single mutation path: write the file (or delete it when
     *  [index] is null) and refresh the cache with a snapshot copy —
     *  callers keep mutating their working maps after a save, and the
     *  cache must never alias one. */
    private fun storeIndex(accountId: String, index: StoredSemanticIndex?) {
        if (index == null) {
            store.delete(accountId)
        } else {
            store.save(accountId, index)
        }
        val snapshot = index?.let {
            StoredSemanticIndex(it.signature, it.dims, LinkedHashMap(it.notes))
        }
        cacheSlot.store(accountId, snapshot)
    }

    private val _rebuildProgress = MutableStateFlow<SemanticRebuildProgress?>(null)

    /** The running rebuild's progress, or null when no rebuild
     *  is running. Read WITHOUT the indexer mutex — that is the
     *  point: the UI keeps reporting while a rebuild holds the
     *  mutex for its whole pass. */
    val rebuildProgress: StateFlow<SemanticRebuildProgress?> =
        _rebuildProgress.asStateFlow()

    /** The text fingerprint a note's stored vectors answer for:
     *  a hash of exactly the text that gets chunked, so any edit
     *  — however small — marks the note for re-embedding. */
    fun fingerprint(title: String, plainText: String): String =
        sha256Hex(NoteChunker.embeddedText(title, plainText))

    /** The fingerprint ONE chunk's stored vector answers for
     *  (G10): a hash of the chunk text alone. Chunk boundaries
     *  are deterministic (see [NoteChunker]), so a stored chunk
     *  at position i still answers for the note's current text
     *  exactly when the live chunk at position i hashes to the
     *  same value — an edit elsewhere in the note leaves it
     *  valid, quotable and searchable. */
    fun chunkFingerprint(chunkText: String): String = sha256Hex(chunkText)

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Performance chapter 2 (F3): the desired set is built from
     *  the (id, title, plainText) projection — never full entities
     *  (contentHtml is never read here) — and [chunks] are computed
     *  LAZILY, only for a note that actually reaches the embedder.
     *  A reconcile over an unchanged library therefore performs no
     *  chunking at all: eligibility and change detection need only
     *  the fingerprint, which hashes the embedded text directly. */
    private class DesiredNote(
        val id: String,
        val title: String,
        val plainText: String,
        val fingerprint: String,
    ) {
        val chunks: List<String> by lazy { NoteChunker.chunk(title, plainText) }
    }

    /** A note that survived Ask's fresh-passage validation:
     *  its live row, the CURRENT chunk texts that still have a
     *  valid stored vector, those vectors, and each chunk's
     *  position in the note's full chunk list (citations name
     *  chunks by that position). Only fingerprint-matching
     *  positions are present — see [validChunkPairs]. */
    private data class AskCandidate(
        val note: NoteEntity,
        val chunks: List<String>,
        val vectors: List<FloatArray>,
        val chunkIndices: List<Int>,
    )

    /** Pairs a note's CURRENT chunks with its stored vectors,
     *  position by position (G10): a pair forms only where the
     *  stored chunk's fingerprint still equals the live chunk's
     *  — the stored vector provably describes the text it would
     *  be quoted as. Positions whose text changed since the
     *  embed (and positions the index never held) form no pair:
     *  their stale vectors can neither be scored nor quoted. */
    private fun validChunkPairs(
        liveChunks: List<String>,
        storedNote: StoredNoteVectors,
    ): List<Triple<Int, String, FloatArray>> {
        val pairs = ArrayList<Triple<Int, String, FloatArray>>()
        for ((index, text) in liveChunks.withIndex()) {
            val storedChunk = storedNote.chunks.getOrNull(index) ?: continue
            if (storedChunk.fingerprint == chunkFingerprint(text)) {
                pairs += Triple(index, text, storedChunk.vector)
            }
        }
        return pairs
    }

    /** Performance chapter 2 (F8): does [storedNote] hold at
     *  least one chunk that still answers for [live]'s current
     *  text? An unchanged note (whole-note fingerprint match)
     *  answers from its stored chunk count alone — no chunker
     *  call; only a changed note pays the chunk + per-chunk
     *  validation. */
    private fun hasFreshChunks(live: NoteEntity, storedNote: StoredNoteVectors): Boolean {
        if (fingerprint(live.title, live.plainText) == storedNote.fingerprint) {
            return storedNote.chunks.isNotEmpty()
        }
        val chunks = NoteChunker.chunk(live.title, live.plainText)
        if (chunks.isEmpty()) return false
        return validChunkPairs(chunks, storedNote).isNotEmpty()
    }

    /** Performance chapter 2 (F8): the valid (index, text,
     *  vector) pairs for [live] against [storedNote] —
     *  [validChunkPairs] with the whole-note fast path in front.
     *  An unchanged note chunks ONCE (the excerpt texts exist
     *  nowhere else; they must be re-derived from the live text)
     *  and hashes NOTHING: fingerprint equality already proves
     *  every position, and the size guard falls back to the
     *  per-chunk rule if the stored shape ever disagreed. */
    private fun freshChunkPairs(
        live: NoteEntity,
        storedNote: StoredNoteVectors,
    ): List<Triple<Int, String, FloatArray>> {
        val chunks = NoteChunker.chunk(live.title, live.plainText)
        if (chunks.isEmpty()) return emptyList()
        if (fingerprint(live.title, live.plainText) == storedNote.fingerprint &&
            storedNote.chunks.size == chunks.size
        ) {
            return chunks.mapIndexed { index, text ->
                Triple(index, text, storedNote.chunks[index].vector)
            }
        }
        return validChunkPairs(chunks, storedNote)
    }

    /** The index's eligibility rule: a note that is locked,
     *  trashed or archived is never embedded and never surfaces.
     *  (Archived matches every other search surface in the app —
     *  FTS and the note lists all require `archived = 0`; semantic
     *  search was the odd one out. Jules PR #23 P2.) Checked
     *  against the snapshot in [desiredNotes] and re-checked
     *  against the LIVE row before every embed. */
    private fun isIndexEligible(note: NoteEntity): Boolean =
        !note.locked && note.trashedAt == null && !note.archived

    /** The notes the index should hold vectors for right now
     *  (active account's database only). Performance chapter 2
     *  (F3): the SQL projection already applies the eligibility
     *  gate (locked / trashed / archived are filtered in the
     *  query), and the blank rule rides [NoteChunker.embeddedText]
     *  — chunk() is empty exactly when the embedded text is
     *  blank, the same equivalence [status] documents — so no
     *  note is chunked here. */
    private suspend fun desiredNotes(): Map<String, DesiredNote> {
        val candidates = databaseProvider.current.noteDao().getIndexCandidates()
        val out = LinkedHashMap<String, DesiredNote>()
        for (row in candidates) {
            if (NoteChunker.embeddedText(row.title, row.plainText).isBlank()) continue
            out[row.id] = DesiredNote(
                row.id, row.title, row.plainText,
                fingerprint(row.title, row.plainText),
            )
        }
        return out
    }

    suspend fun status(accountId: String): SemanticIndexStatus = mutex.withLock {
        val config = settings.activeEmbeddingConfig(accountId)
        val stored = loadIndex(accountId)
        val eligible = if (accountId == databaseProvider.accountId.value) {
            // Phase 6.1: the eligible COUNT used to cost a full
            // desiredNotes() pass — every note's entity loaded, its
            // text chunked and SHA-256-hashed — on every Ask-screen
            // open and Settings view. The count needs only the
            // (id, title, plainText) projection and the chunker's own
            // blank rule: chunk() is empty exactly when embeddedText
            // is blank.
            databaseProvider.current.noteDao().getIndexCandidates().count { candidate ->
                NoteChunker.embeddedText(candidate.title, candidate.plainText).isNotBlank()
            }
        } else {
            stored?.notes?.size ?: 0
        }
        SemanticIndexStatus(
            indexedNotes = stored?.notes?.size ?: 0,
            eligibleNotes = eligible,
            canIndex = config != null,
            spaceIsCurrent = stored != null && config != null &&
                stored.signature == config.spaceSignature(),
        )
    }

    /**
     * Full (re)build: embeds every eligible note into a
     * replacement index held in memory, then swaps it in with a
     * single save at the end. The stored index is NOT touched
     * while the build runs — it keeps serving [status] and
     * [search] at its previous count for the whole pass — and a
     * provider failure mid-build leaves it completely intact,
     * with the exception in the caller's hands (Settings surfaces
     * it); [reconcile] is the silent repair. Progress is
     * published on [rebuildProgress] as the pass advances.
     *
     * The pre-fix build deleted the stored index first and
     * re-persisted in groups, so the entire build window read
     * as "no notes indexed yet" and search served nothing —
     * the Settings wart this build-then-swap removes. The swap
     * still never mixes vectors from a previous space: the
     * replacement is built from scratch and lands whole.
     */
    suspend fun rebuild(accountId: String) {
        val config = settings.activeEmbeddingConfig(accountId)
            ?: throw AiException.noKey()
        if (accountId != databaseProvider.accountId.value) return
        mutex.withLock {
            val stampAtStart = databaseProvider.current.noteDao().getLibraryStampRow()
            val desired = desiredNotes()
            _rebuildProgress.value =
                SemanticRebuildProgress(accountId, done = 0, total = desired.size)
            try {
                val working = LinkedHashMap<String, StoredNoteVectors>()
                var dims = 0
                val noteDao = databaseProvider.current.noteDao()
                var done = 0
                for (want in desired.values) {
                    // Snapshot-vs-live re-check (Jules PR #23 P1): the
                    // desired set was computed at pass start; a note
                    // locked, trashed or archived SINCE then must not
                    // be embedded from its stale snapshot — above all
                    // a locked note's plaintext must never reach the
                    // provider.
                    val live = noteDao.getById(want.id)
                    if (live != null && isIndexEligible(live)) {
                        val chunks = embedChunks(config, want, existing = null, dims)
                        if (dims == 0) dims = chunks.first().vector.size
                        working[want.id] =
                            StoredNoteVectors(want.fingerprint, chunks)
                    }
                    _rebuildProgress.value =
                        SemanticRebuildProgress(accountId, ++done, desired.size)
                }
                // The swap: the store's temp-then-rename write
                // replaces the old index atomically. When nothing
                // is eligible any more the swap still leaves an
                // (empty) index behind, signed with the active
                // space: a completed pass must leave the store in
                // a state the reconcile stamp gate can hit, and a
                // deleted index can never satisfy the gate's
                // stored-index check (Codex PR #54 R2). Readers
                // treat an empty index exactly like a missing one
                // (search and Ask both check notes.isEmpty()), so
                // no answer changes — only the gate can now hit.
                if (working.isEmpty()) {
                    storeIndex(
                        accountId,
                        StoredSemanticIndex(config.spaceSignature(), 0, emptyMap()),
                    )
                } else {
                    storeIndex(
                        accountId,
                        StoredSemanticIndex(config.spaceSignature(), dims, working),
                    )
                }
                // A completed rebuild leaves the index answering
                // for exactly the library the stamp described at
                // pass start (F3): record it so the next reconcile
                // can gate on it.
                store.saveStamp(accountId, stampAtStart)
            } finally {
                _rebuildProgress.value = null
            }
        }
    }

    /**
     * The maintenance pass: purge what must not be indexed, embed
     * what is new or changed. NEVER throws — indexing is
     * best-effort and a failure must not reach a note save, a
     * search, or app start; whatever was missed reads as stale
     * and the next trigger retries. The purge half needs no
     * provider config and runs even with the key removed: the
     * locked-note invariant does not depend on AI being usable.
     */
    suspend fun reconcile(accountId: String) {
        try {
            if (accountId != databaseProvider.accountId.value) return
            mutex.withLock { reconcileLocked(accountId) }
        } catch (e: Exception) {
            // Best-effort by design (see class doc). Cancellation
            // is not swallowed: it is not an Exception here, it is
            // a CancellationException — which IS an Exception on
            // the JVM, so rethrow it explicitly.
            if (e is kotlinx.coroutines.CancellationException) throw e
        }
    }

    /**
     * The USER-INITIATED build (the Ask gate's Build action):
     * the same maintenance pass as [reconcile], but a provider
     * failure surfaces to the caller as [AiException] instead
     * of being swallowed — a person who just tapped Build is
     * owed an error state, not the Build button silently
     * re-appearing as if nothing happened (Codex PR #25 P2;
     * [rebuild] already surfaces this way). Background
     * triggers keep using [reconcile]: indexing must never
     * fail a note save, a search, or app start. Partial
     * progress made before the failure stays persisted,
     * exactly as in the background pass.
     */
    suspend fun buildIndex(accountId: String) {
        if (accountId != databaseProvider.accountId.value) return
        mutex.withLock { reconcileLocked(accountId, surfaceFailure = true) }
    }

    private suspend fun reconcileLocked(accountId: String, surfaceFailure: Boolean = false) {
        val config = settings.activeEmbeddingConfig(accountId)
        val stored = loadIndex(accountId)
        val noteDao = databaseProvider.current.noteDao()

        // Performance chapter 2 (F3): the stamp gate. One
        // aggregate query describes the whole notes table; when
        // it equals the stamp the last COMPLETED pass recorded,
        // and the stored index still belongs to the active
        // embedding space, the library is exactly the one that
        // pass already reconciled — purged, embedded, current —
        // and this pass has nothing to do. It returns before
        // loading a single note body, chunk or hash. The stamp
        // is only ever written at the END of a pass that ran to
        // completion (below and in [rebuild]); a pass cut short
        // by a provider failure leaves the old stamp in place,
        // so the next trigger re-runs the full pass.
        val stampAtStart = noteDao.getLibraryStampRow()
        val savedStamp = store.loadStamp(accountId)
        if (savedStamp != null && savedStamp == stampAtStart &&
            (config == null ||
                (stored != null && stored.signature == config.spaceSignature()))
        ) {
            return
        }

        val desired = desiredNotes()

        // Foreign-space vectors are dropped wholesale (1.x rule),
        // and with them the stored dimension: a new space's dims
        // are established by its first vector, never inherited.
        val spaceMatches = stored != null && config != null &&
            stored.signature == config.spaceSignature()
        val working = LinkedHashMap<String, StoredNoteVectors>()
        var dims = 0
        if (stored != null && (config == null || spaceMatches)) {
            working.putAll(stored.notes)
            dims = stored.dims
        }

        // Purge: gone, locked, trashed, archived or emptied notes
        // lose their vectors — locally, before any provider call
        // is made.
        val iterator = working.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key !in desired) iterator.remove()
        }
        if (config == null) {
            // The purge/retain half persists exactly as always —
            // but the stamp is NOT advanced (Codex PR #54 R1): it
            // records the last COMPLETED pass, and this path
            // embedded nothing. Stamping here would gate out the
            // next configured reconcile and leave everything that
            // changed while unconfigured unindexed.
            persist(accountId, stored?.signature, dims, working)
            return
        }

        // Embed new/changed notes; on the first provider failure
        // stop and keep what succeeded (the rest stays stale).
        var changed = working.size != (stored?.notes?.size ?: 0)
        var sinceSave = 0
        var providerFailed = false
        for ((noteId, want) in desired) {
            // Snapshot-vs-live re-check (Jules PR #23 P1): the
            // snapshot predates any lock/trash/archive that landed
            // while this pass was embedding earlier notes. Such a
            // note is not embedded from its stale snapshot, and
            // vectors it already had do not survive the pass
            // either — the purge above only saw the snapshot.
            // (Performance chapter 2 kept this per-note check
            // deliberately: a batched eligibility snapshot taken
            // before the loop cannot see a lock that lands
            // MID-PASS, and a locked note's plaintext must never
            // reach the provider — the noteLockedMidPass pin.)
            val live = noteDao.getById(noteId)
            if (live == null || !isIndexEligible(live)) {
                if (working.remove(noteId) != null) changed = true
                continue
            }
            val existing = working[noteId]
            if (existing != null && existing.fingerprint == want.fingerprint) continue
            val chunks = try {
                embedChunks(config, want, existing, dims)
            } catch (e: AiException) {
                // Background passes stop quietly and keep what
                // succeeded; a user-initiated build is owed the
                // failure itself (see [buildIndex]).
                if (surfaceFailure) throw e
                providerFailed = true
                break
            }
            if (dims == 0) dims = chunks.first().vector.size
            working[noteId] = StoredNoteVectors(want.fingerprint, chunks)
            changed = true
            if (++sinceSave >= SAVE_EVERY_NOTES) {
                storeIndex(accountId, StoredSemanticIndex(config.spaceSignature(), dims, working))
                sinceSave = 0
            }
        }
        if (working.isEmpty() && !providerFailed) {
            // A completed configured pass that leaves nothing
            // indexed still stores an (empty) index under the
            // active signature (Codex PR #54 R2): persist() would
            // DELETE the file for an empty working set, and a
            // missing index can never satisfy the stamp gate's
            // stored-index check — a library with zero indexable
            // notes, or one purged to empty above, would re-run
            // the full candidate scan on every reconcile despite
            // the stamp saved below. An empty index reads exactly
            // like a missing one in search/Ask (both check
            // notes.isEmpty()), so no answer changes. A provider-
            // failed pass is NOT completed: it falls through to
            // the persist path below exactly as before, so a
            // failure still leaves the previous state (or no
            // index at all) untouched.
            storeIndex(
                accountId,
                StoredSemanticIndex(config.spaceSignature(), 0, emptyMap()),
            )
        } else if (changed) {
            persist(accountId, config.spaceSignature(), dims, working)
        }
        // The pass ran to completion: record the stamp it
        // answered for, so the next trigger can gate on it. A
        // provider failure leaves the previous stamp untouched —
        // the unembedded remainder still needs a full pass.
        if (!providerFailed) {
            store.saveStamp(accountId, stampAtStart)
        }
    }

    /**
     * Meaning-based search over the stored index. Returns no hits
     * — never throws for configuration problems — when semantic
     * search cannot run (no key, no index, foreign space, offline
     * query failure); the keyword results simply stand alone. An
     * empty or foreign index triggers one reconcile first (the
     * first-use backfill and the space-change repair), exactly
     * like 1.x's build-on-first-use contract.
     */
    suspend fun search(accountId: String, query: String): List<SemanticHit> {
        if (query.isBlank()) return emptyList()
        val config = settings.activeEmbeddingConfig(accountId) ?: return emptyList()
        if (accountId != databaseProvider.accountId.value) return emptyList()
        var stored = loadIndex(accountId)
        if (stored == null || stored.notes.isEmpty() ||
            stored.signature != config.spaceSignature()
        ) {
            reconcile(accountId)
            stored = loadIndex(accountId)
        }
        if (stored == null || stored.notes.isEmpty() ||
            stored.signature != config.spaceSignature()
        ) {
            return emptyList()
        }
        val queryVector = try {
            AiEmbeddings.create(config.providerId, http)
                .embed(config, query, EmbedKind.QUERY)
        } catch (e: AiException) {
            return emptyList()
        }
        if (queryVector.size != stored.dims) return emptyList()
        // Defence in depth (1.x's merge rule), applied BEFORE
        // scoring so stale entries never consume the hit limit:
        // a note only ever surfaces when it is eligible RIGHT
        // NOW — a stale vector can never resurrect a locked/
        // trashed/archived/deleted note into the results.
        //
        // Freshness is PER CHUNK (G10): each note contributes a
        // position-aligned vector list over its CURRENT chunks,
        // where a position holds its stored vector only when
        // that chunk's fingerprint still matches the live chunk
        // text there ([validChunkPairs] is Ask's form of the
        // same rule) and null otherwise. Between an edit and the
        // next reconcile, the changed chunks go silent — neither
        // a search boost nor a meaning-only row may ride on
        // them — while the note's untouched chunks keep it
        // findable by the meaning it still contains.
        // ONE batch read for every live row (Codex PR #49 R1):
        // the eligibility-filtered projection the status count
        // already uses. The per-note getById loop this replaces
        // cost a Room round trip per indexed note on every
        // search; a stored note missing from the batch is gone
        // or ineligible — exactly the rows that loop skipped.
        val liveById = databaseProvider.current.noteDao()
            .getIndexCandidates().associateBy { it.id }
        val aligned = LinkedHashMap<String, List<FloatArray?>>()
        for ((noteId, storedNote) in stored.notes) {
            val live = liveById[noteId] ?: continue
            // Performance chapter 2 (F8): the whole-note
            // fingerprint fast path. When it matches, the live
            // text is identical to the text the stored vectors
            // were embedded from, so re-chunking would
            // deterministically reproduce the stored chunking
            // and every per-chunk fingerprint check would pass:
            // the stored vectors align by position with no
            // chunker call and no per-chunk hashing. Fingerprint
            // equality is strictly stronger than the per-chunk
            // rule (it pins the WHOLE text, not one position),
            // so the aligned result is unchanged.
            if (fingerprint(live.title, live.plainText) == storedNote.fingerprint) {
                if (storedNote.chunks.isEmpty()) continue
                aligned[noteId] = storedNote.chunks.map { it.vector }
                continue
            }
            val liveChunks = NoteChunker.chunk(live.title, live.plainText)
            if (liveChunks.isEmpty()) continue
            val pairs = validChunkPairs(liveChunks, storedNote)
            if (pairs.isEmpty()) continue
            val vectors = arrayOfNulls<FloatArray>(liveChunks.size)
            for ((index, _, vector) in pairs) vectors[index] = vector
            aligned[noteId] = vectors.asList()
        }
        if (aligned.isEmpty()) return emptyList()
        return SemanticMath.relatedHits(
            queryVector, aligned,
            SemanticMath.floorFor(config.resolvedEmbeddingModel()),
            limit = SEARCH_HIT_LIMIT,
        )
    }

    /**
     * Ask Nota's retrieval (Phase 4.3): the best passages for a
     * question, scored chunk-by-chunk over the stored index —
     * 1.x's retrieveChunks contract on Nota's index. Returns
     * empty — and makes NO provider call — when there is
     * nothing to retrieve from (no key, no index, a foreign
     * space, or no note passing validation); the caller reads
     * that as the honest no-evidence state. A failure of the
     * question-embedding call itself propagates as
     * [AiException]: offline must surface as an error, never
     * dress itself up as "nothing matched".
     *
     * Fresh-passage validation: a stored vector is only ever
     * a claim about the chunk text that produced it, so a note
     * contributes passages only when its LIVE row is eligible
     * right now ([isIndexEligible] — locked, trashed, archived
     * and deleted notes are out, however high they would
     * score) AND at least one of its stored chunks still
     * answers for the note's current text at the same position
     * (per-chunk fingerprints, G10 — 1.x's granularity: an
     * edited note keeps its untouched chunks quotable, while
     * the changed chunk's stale vector can never surface,
     * because quoted text is re-chunked from the note's
     * CURRENT text and a fingerprint match is what licenses
     * pairing that text with the stored vector at all). The
     * validation runs TWICE — before the question-embedding
     * call and again after it returns — because the call takes
     * seconds and a state change landing inside it must be
     * honoured by the retrieval already in flight, not just
     * the next one.
     *
     * Ranking is 1.x's: similarity descending, note id then
     * chunk index breaking ties so citation numbering is
     * deterministic; selection caps at [ASK_MAX_EXCERPTS]
     * passages, at most [ASK_MAX_EXCERPTS_PER_NOTE] per note.
     */
    suspend fun retrieveAskExcerpts(accountId: String, question: String): List<AskExcerpt> {
        if (question.isBlank()) return emptyList()
        val config = settings.activeEmbeddingConfig(accountId) ?: return emptyList()
        if (accountId != databaseProvider.accountId.value) return emptyList()
        val stored = loadIndex(accountId) ?: return emptyList()
        if (stored.notes.isEmpty() || stored.signature != config.spaceSignature()) {
            return emptyList()
        }

        val noteDao = databaseProvider.current.noteDao()
        // Performance chapter 2 (F8): the pre-embed pass is a
        // VALIDITY pass — it decides only whether anything
        // retrievable exists, which is what gates the provider
        // call below. Unchanged notes answer from the whole-note
        // fingerprint with no chunker call at all; the excerpt
        // texts themselves are derived once, after the embed, by
        // the revalidation pass.
        val preValid = ArrayList<Pair<NoteEntity, StoredNoteVectors>>()
        for ((noteId, storedNote) in stored.notes) {
            val live = noteDao.getById(noteId) ?: continue
            if (!isIndexEligible(live)) continue
            if (!hasFreshChunks(live, storedNote)) continue
            preValid += live to storedNote
        }
        if (preValid.isEmpty()) return emptyList()

        val queryVector = AiEmbeddings.create(config.providerId, http)
            .embed(config, question, EmbedKind.QUERY)
        if (queryVector.size != stored.dims) return emptyList()
        val floor = SemanticMath.floorFor(config.resolvedEmbeddingModel())

        // Post-embed revalidation (Codex PR #25 P1): the
        // candidates above were validated BEFORE the embedding
        // call, and that call takes seconds on a phone — a note
        // locked, archived, trashed, deleted or edited while it
        // was in flight must not contribute passages from its
        // pre-request snapshot. Re-check every candidate
        // against its LIVE row — eligibility AND, per chunk,
        // the stored fingerprint — exactly as [search]
        // validates its own scoring input. The candidate is
        // REBUILT from the live row, so scoring proceeds on the
        // note's current chunks and only the positions whose
        // fingerprints still license them.
        val liveCandidates = ArrayList<AskCandidate>(preValid.size)
        for ((snapshot, storedNote) in preValid) {
            val live = noteDao.getById(snapshot.id) ?: continue
            if (!isIndexEligible(live)) continue
            val pairs = freshChunkPairs(live, storedNote)
            if (pairs.isEmpty()) continue
            liveCandidates += AskCandidate(
                live,
                pairs.map { it.second },
                pairs.map { it.third },
                pairs.map { it.first },
            )
        }
        if (liveCandidates.isEmpty()) return emptyList()

        val scored = ArrayList<AskExcerpt>()
        for (candidate in liveCandidates) {
            // Chunks and vectors are paired by construction;
            // the min is a defensive belt, never the expected
            // path. Citations name the chunk's position in the
            // note's full chunk list, not its rank among the
            // valid pairs.
            val count = minOf(candidate.chunks.size, candidate.vectors.size)
            for (i in 0 until count) {
                val score = SemanticMath.cosine(queryVector, candidate.vectors[i])
                if (score >= floor) {
                    scored += AskExcerpt(
                        noteId = candidate.note.id,
                        chunkIndex = candidate.chunkIndices[i],
                        title = candidate.note.title,
                        text = candidate.chunks[i],
                        similarity = score,
                    )
                }
            }
        }
        scored.sortWith(
            compareByDescending<AskExcerpt> { it.similarity }
                .thenBy { it.noteId }
                .thenBy { it.chunkIndex },
        )
        val perNote = HashMap<String, Int>()
        val out = ArrayList<AskExcerpt>(ASK_MAX_EXCERPTS)
        for (item in scored) {
            if (out.size >= ASK_MAX_EXCERPTS) break
            val count = perNote[item.noteId] ?: 0
            if (count >= ASK_MAX_EXCERPTS_PER_NOTE) continue
            perNote[item.noteId] = count + 1
            out += item
        }
        return out
    }

    /**
     * Post-generation revalidation for Ask (Jules PR #25 P1,
     * re-based on retrieval-time state in PR #49): of the
     * answer's [citations], the numbers whose quoted passage
     * is STILL the note's current text. A citation survives
     * exactly when its note is live and eligible right now
     * ([isIndexEligible] — locked, trashed, archived and
     * deleted notes are out) AND the chunk at the citation's
     * position in the note's CURRENT chunking still hashes
     * to the fingerprint of the excerpt text that was
     * actually sent to the model.
     *
     * Why retrieval-time state and not the stored index
     * (Codex PR #49 R1): retrieval already validated every
     * excerpt against the note's live text, pairing text
     * with a stored vector per chunk (G10) — so an edit that
     * landed BEFORE retrieval, in a different chunk, with
     * the reconcile still pending, must not kill a citation
     * to an untouched passage. The previous form compared
     * the whole note against the stale stored fingerprint
     * and did exactly that, degrading valid answers to
     * refusals in precisely the window G10 opened. What this
     * gate still guarantees, unchanged from PR #25: a change
     * landing DURING generation in the cited chunk itself —
     * or shifting chunk boundaries so the cited position
     * now holds different text — retires that citation
     * before anything renders. Sent excerpts carry whole
     * chunks at the prompt's current caps, so the fingerprint
     * comparison is exact. Local reads only — no provider,
     * no config — and fail-closed: a note that cannot be
     * re-read, or a citation naming an excerpt that was
     * never sent, counts as no longer valid.
     */
    suspend fun revalidateAskCitations(
        accountId: String,
        sentExcerpts: List<AskExcerpt>,
        citations: List<AskCitation>,
    ): Set<Int> {
        if (citations.isEmpty()) return emptySet()
        if (accountId != databaseProvider.accountId.value) return emptySet()
        val sentByChunk = HashMap<Pair<String, Int>, AskExcerpt>()
        for (excerpt in sentExcerpts) {
            sentByChunk[excerpt.noteId to excerpt.chunkIndex] = excerpt
        }
        val noteDao = databaseProvider.current.noteDao()
        val storedIndex = loadIndex(accountId)
        val valid = HashSet<Int>()
        for (citation in citations) {
            val excerpt = sentByChunk[citation.noteId to citation.chunkIndex]
                ?: continue
            val live = try {
                noteDao.getById(citation.noteId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } ?: continue
            if (!isIndexEligible(live)) continue
            // Performance chapter 2 (F8): when the note is
            // unchanged since its embed (whole-note fingerprint
            // match), the current chunk at the cited position IS
            // the stored chunk, so validity reduces to comparing
            // the excerpt against the stored chunk fingerprint —
            // no chunker call, no re-chunk of a possibly huge
            // note per citation. The check is exact (the stored
            // fingerprint hashes the very text the position
            // holds), and any doubt — no index, no stored entry,
            // a changed note — falls through to the chunked
            // comparison below, unchanged.
            val storedNote = storedIndex?.notes?.get(citation.noteId)
            if (storedNote != null &&
                fingerprint(live.title, live.plainText) == storedNote.fingerprint
            ) {
                val storedChunk = storedNote.chunks.getOrNull(citation.chunkIndex)
                if (storedChunk != null &&
                    storedChunk.fingerprint == chunkFingerprint(excerpt.text)
                ) {
                    valid += citation.n
                }
                continue
            }
            val liveChunk = NoteChunker.chunk(live.title, live.plainText)
                .getOrNull(citation.chunkIndex) ?: continue
            if (chunkFingerprint(liveChunk) != chunkFingerprint(excerpt.text)) {
                continue
            }
            valid += citation.n
        }
        return valid
    }

    /** Drops the account's whole index (sign-out — like its keys,
     *  an account's vectors belong to the sign-in). */
    suspend fun clearAccount(accountId: String) {
        mutex.withLock { storeIndex(accountId, null) }
    }

    /**
     * Drops ONE note's vectors immediately (Jules PR #23 P1):
     * called by the lock flow the moment a note locks, so its
     * plaintext-derived vectors do not linger in the on-disk
     * index until the 15 s maintenance debounce fires — or
     * forever, if the process dies first. Local file work only:
     * no provider, no config, so it is safe with no key saved.
     * Serialised with reconcile/rebuild by the same mutex, so it
     * cannot interleave with a running pass — and a pass already
     * running honours the lock through its live re-check.
     */
    suspend fun evictNote(accountId: String, noteId: String) {
        mutex.withLock {
            val stored = loadIndex(accountId) ?: return
            if (noteId !in stored.notes) return
            val working = LinkedHashMap(stored.notes)
            working.remove(noteId)
            persist(accountId, stored.signature, stored.dims, working)
        }
    }

    /** Embeds one note's chunks, enforcing a single dimension
     *  across the whole index (a provider that changes dims
     *  mid-run fails loudly rather than storing a mixed space —
     *  the 1.x rule). [expectedDims] 0 = not yet established.
     *
     *  Per-chunk reuse (G10): a position whose stored chunk
     *  fingerprint still matches the desired chunk text keeps
     *  its stored vector — chunking is deterministic, so the
     *  vector provably describes that exact text — and only the
     *  changed or missing chunk texts go to the provider, in
     *  ONE embedAll call per note, spliced back into position
     *  order. A one-chunk edit to a long note therefore costs
     *  one embedding, not a whole note's worth. [existing] is
     *  null on a from-scratch build, where every chunk embeds. */
    private suspend fun embedChunks(
        config: AiEmbeddingConfig,
        desired: DesiredNote,
        existing: StoredNoteVectors?,
        expectedDims: Int,
    ): List<StoredChunk> {
        val out = arrayOfNulls<StoredChunk>(desired.chunks.size)
        val pendingTexts = ArrayList<String>()
        val pendingPositions = ArrayList<Int>()
        for ((index, text) in desired.chunks.withIndex()) {
            val storedChunk = existing?.chunks?.getOrNull(index)
            if (storedChunk != null &&
                storedChunk.fingerprint == chunkFingerprint(text)
            ) {
                out[index] = storedChunk
            } else {
                pendingTexts += text
                pendingPositions += index
            }
        }
        if (pendingTexts.isNotEmpty()) {
            val vectors = AiEmbeddings.create(config.providerId, http)
                .embedAll(config, pendingTexts, EmbedKind.DOCUMENT)
            if (vectors.size != pendingTexts.size) {
                throw AiException(AiErrorKind.BAD_RESPONSE)
            }
            // Reused vectors pin the space's dimension too: a
            // fresh vector must agree with them exactly as it
            // must agree with [expectedDims].
            var dims = expectedDims
            if (dims == 0) {
                dims = out.firstOrNull { it != null }?.vector?.size ?: 0
            }
            for ((slot, vector) in pendingPositions.zip(vectors)) {
                if (vector.isEmpty()) throw AiException(AiErrorKind.BAD_RESPONSE)
                if (dims == 0) dims = vector.size
                if (vector.size != dims) throw AiException(AiErrorKind.BAD_RESPONSE)
                out[slot] = StoredChunk(chunkFingerprint(desired.chunks[slot]), vector)
            }
        }
        return out.map { it!! }
    }

    private fun persist(
        accountId: String,
        signature: String?,
        dims: Int,
        working: Map<String, StoredNoteVectors>,
    ) {
        if (working.isEmpty() || signature == null) {
            storeIndex(accountId, null)
        } else {
            storeIndex(accountId, StoredSemanticIndex(signature, dims, LinkedHashMap(working)))
        }
    }

    /**
     * The similarity floor the active embedding config's model
     * carries (Search Quality Pass: floors follow the MODEL —
     * see [SemanticMath.floorFor]) — the same value [search] and
     * Ask retrieval apply, exposed so the search UI's blend
     * judges hits by the identical threshold instead of a second
     * copy of the rule. [SemanticMath.DEFAULT_FLOOR] when no
     * config resolves (semantic search cannot run then anyway,
     * so the value is never actually applied to a hit).
     */
    suspend fun similarityFloor(accountId: String): Double {
        val config = settings.activeEmbeddingConfig(accountId) ?: return SemanticMath.DEFAULT_FLOOR
        return SemanticMath.floorFor(config.resolvedEmbeddingModel())
    }

    private companion object {
        const val SAVE_EVERY_NOTES = 25

        /** How many meaning hits one search returns (Search
         *  Quality Pass): the blend ranks semantic-only notes
         *  INTO the keyword list, so the Phase 4.2 section cap
         *  of 10 would silently drop blend candidates; 50 keeps
         *  the pass bounded while covering any realistic set of
         *  above-floor notes in a personal library. */
        const val SEARCH_HIT_LIMIT = 50
    }
}
