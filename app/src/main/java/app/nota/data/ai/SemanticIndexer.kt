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

    private data class DesiredNote(
        val note: NoteEntity,
        val fingerprint: String,
        val chunks: List<String>,
    )

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
     *  (active account's database only). */
    private suspend fun desiredNotes(): Map<String, DesiredNote> {
        val all = databaseProvider.current.noteDao().getAll()
        val out = LinkedHashMap<String, DesiredNote>()
        for (note in all) {
            if (!isIndexEligible(note)) continue
            val chunks = NoteChunker.chunk(note.title, note.plainText)
            if (chunks.isEmpty()) continue
            out[note.id] = DesiredNote(note, fingerprint(note.title, note.plainText), chunks)
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
                    val live = noteDao.getById(want.note.id)
                    if (live != null && isIndexEligible(live)) {
                        val chunks = embedChunks(config, want, existing = null, dims)
                        if (dims == 0) dims = chunks.first().vector.size
                        working[want.note.id] =
                            StoredNoteVectors(want.fingerprint, chunks)
                    }
                    _rebuildProgress.value =
                        SemanticRebuildProgress(accountId, ++done, desired.size)
                }
                // The swap: the store's temp-then-rename write
                // replaces the old index atomically — or the file
                // goes, when nothing is eligible any more.
                if (working.isEmpty()) {
                    storeIndex(accountId, null)
                } else {
                    storeIndex(
                        accountId,
                        StoredSemanticIndex(config.spaceSignature(), dims, working),
                    )
                }
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
            persist(accountId, stored?.signature, dims, working)
            return
        }

        // Embed new/changed notes; on the first provider failure
        // stop and keep what succeeded (the rest stays stale).
        var changed = working.size != (stored?.notes?.size ?: 0)
        var sinceSave = 0
        val noteDao = databaseProvider.current.noteDao()
        for ((noteId, want) in desired) {
            // Snapshot-vs-live re-check (Jules PR #23 P1): the
            // snapshot predates any lock/trash/archive that landed
            // while this pass was embedding earlier notes. Such a
            // note is not embedded from its stale snapshot, and
            // vectors it already had do not survive the pass
            // either — the purge above only saw the snapshot.
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
        if (changed) {
            persist(accountId, config.spaceSignature(), dims, working)
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
        val noteDao = databaseProvider.current.noteDao()
        val aligned = LinkedHashMap<String, List<FloatArray?>>()
        for ((noteId, storedNote) in stored.notes) {
            val note = noteDao.getById(noteId) ?: continue
            if (!isIndexEligible(note)) continue
            val liveChunks = NoteChunker.chunk(note.title, note.plainText)
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
        val candidates = ArrayList<AskCandidate>()
        for ((noteId, storedNote) in stored.notes) {
            val live = noteDao.getById(noteId) ?: continue
            if (!isIndexEligible(live)) continue
            val chunks = NoteChunker.chunk(live.title, live.plainText)
            if (chunks.isEmpty()) continue
            val pairs = validChunkPairs(chunks, storedNote)
            if (pairs.isEmpty()) continue
            candidates += AskCandidate(
                live,
                pairs.map { it.second },
                pairs.map { it.third },
                pairs.map { it.first },
            )
        }
        if (candidates.isEmpty()) return emptyList()

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
        val liveCandidates = ArrayList<AskCandidate>(candidates.size)
        for (candidate in candidates) {
            val live = noteDao.getById(candidate.note.id) ?: continue
            if (!isIndexEligible(live)) continue
            val storedNote = stored.notes[candidate.note.id] ?: continue
            val chunks = NoteChunker.chunk(live.title, live.plainText)
            if (chunks.isEmpty()) continue
            val pairs = validChunkPairs(chunks, storedNote)
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
     * Post-generation revalidation for Ask (Jules PR #25 P1):
     * of [noteIds], the ones whose note is STILL quotable right
     * now — live row eligible ([isIndexEligible]) AND its
     * current text's fingerprint still equal to the stored one.
     * Deliberately NOTE-strict even though retrieval validates
     * per chunk (G10): this is the post-generation citation
     * gate, where any edit since retrieval retires the note's
     * citations wholesale — the stricter rule is the safe one
     * for text already shown to the model.
     * Ask calls this AFTER the generation call returns and
     * before the answer renders: a note locked, archived,
     * trashed, deleted or edited while the model was answering
     * loses its citations (and with them its source card) even
     * though it was valid when retrieved. Local reads only —
     * no provider, no config — and fail-closed: a note that
     * cannot be re-read counts as no longer valid rather than
     * letting an unverifiable citation render.
     */
    suspend fun revalidateAskNotes(
        accountId: String,
        noteIds: Collection<String>,
    ): Set<String> {
        if (noteIds.isEmpty()) return emptySet()
        if (accountId != databaseProvider.accountId.value) return emptySet()
        val stored = try {
            loadIndex(accountId)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return emptySet()
        } ?: return emptySet()
        val noteDao = databaseProvider.current.noteDao()
        val valid = HashSet<String>()
        for (noteId in noteIds) {
            val storedNote = stored.notes[noteId] ?: continue
            val live = try {
                noteDao.getById(noteId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            } ?: continue
            if (!isIndexEligible(live)) continue
            if (fingerprint(live.title, live.plainText) != storedNote.fingerprint) continue
            valid += noteId
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
