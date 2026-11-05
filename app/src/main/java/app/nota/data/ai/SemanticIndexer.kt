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
    fun fingerprint(title: String, plainText: String): String {
        val text = NoteChunker.embeddedText(title, plainText)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class DesiredNote(
        val note: NoteEntity,
        val fingerprint: String,
        val chunks: List<String>,
    )

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
        val stored = store.load(accountId)
        val eligible = if (accountId == databaseProvider.accountId.value) {
            desiredNotes().size
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
                        val vectors = embedNote(config, want, dims)
                        if (dims == 0) dims = vectors.first().size
                        working[want.note.id] =
                            StoredNoteVectors(want.fingerprint, vectors)
                    }
                    _rebuildProgress.value =
                        SemanticRebuildProgress(accountId, ++done, desired.size)
                }
                // The swap: the store's temp-then-rename write
                // replaces the old index atomically — or the file
                // goes, when nothing is eligible any more.
                if (working.isEmpty()) {
                    store.delete(accountId)
                } else {
                    store.save(
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

    private suspend fun reconcileLocked(accountId: String) {
        val config = settings.activeEmbeddingConfig(accountId)
        val stored = store.load(accountId)
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
            val vectors = try {
                embedNote(config, want, dims)
            } catch (e: AiException) {
                break
            }
            if (dims == 0) dims = vectors.first().size
            working[noteId] = StoredNoteVectors(want.fingerprint, vectors)
            changed = true
            if (++sinceSave >= SAVE_EVERY_NOTES) {
                store.save(accountId, StoredSemanticIndex(config.spaceSignature(), dims, working))
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
        var stored = store.load(accountId)
        if (stored == null || stored.notes.isEmpty() ||
            stored.signature != config.spaceSignature()
        ) {
            reconcile(accountId)
            stored = store.load(accountId)
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
        val chunksByNote = stored.notes.mapValues { it.value.chunks }
        val raw = SemanticMath.relatedHits(
            queryVector, chunksByNote, SemanticMath.floorFor(config.providerId),
        )
        if (raw.isEmpty()) return raw
        // Defence in depth (1.x's merge rule): a hit only ever
        // surfaces a note that is eligible RIGHT NOW — a stale
        // vector can never resurrect a locked/trashed/archived/
        // deleted note into the results.
        val noteDao = databaseProvider.current.noteDao()
        return raw.filter { hit ->
            val note = noteDao.getById(hit.noteId)
            note != null && isIndexEligible(note)
        }
    }

    /** Drops the account's whole index (sign-out — like its keys,
     *  an account's vectors belong to the sign-in). */
    suspend fun clearAccount(accountId: String) {
        mutex.withLock { store.delete(accountId) }
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
            val stored = store.load(accountId) ?: return
            if (noteId !in stored.notes) return
            val working = LinkedHashMap(stored.notes)
            working.remove(noteId)
            persist(accountId, stored.signature, stored.dims, working)
        }
    }

    /** Embeds one note's chunks, enforcing a single dimension
     *  across the whole index (a provider that changes dims
     *  mid-run fails loudly rather than storing a mixed space —
     *  the 1.x rule). [expectedDims] 0 = not yet established. */
    private suspend fun embedNote(
        config: AiEmbeddingConfig,
        desired: DesiredNote,
        expectedDims: Int,
    ): List<FloatArray> {
        val vectors = AiEmbeddings.create(config.providerId, http)
            .embedAll(config, desired.chunks, EmbedKind.DOCUMENT)
        if (vectors.size != desired.chunks.size) throw AiException(AiErrorKind.BAD_RESPONSE)
        var dims = expectedDims
        for (vector in vectors) {
            if (vector.isEmpty()) throw AiException(AiErrorKind.BAD_RESPONSE)
            if (dims == 0) dims = vector.size
            if (vector.size != dims) throw AiException(AiErrorKind.BAD_RESPONSE)
        }
        return vectors
    }

    private fun persist(
        accountId: String,
        signature: String?,
        dims: Int,
        working: Map<String, StoredNoteVectors>,
    ) {
        if (working.isEmpty() || signature == null) {
            store.delete(accountId)
        } else {
            store.save(accountId, StoredSemanticIndex(signature, dims, LinkedHashMap(working)))
        }
    }

    private companion object {
        const val SAVE_EVERY_NOTES = 25
    }
}
