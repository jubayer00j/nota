package app.nota.data.ai

import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NoteEntity
import java.security.MessageDigest
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

/**
 * Semantic search's engine room (Phase 4.2): owns the lifecycle
 * of the per-account vector index — build, reconcile, query,
 * drop — over [SemanticIndexStore].
 *
 * The rules, carried from 1.x #8.5 and hardened for the native
 * per-note lock:
 *
 * - **Locked notes are NEVER indexed.** Eligibility is
 *   `!locked && !trashed` and the reconcile purge enforces it
 *   locally, with no provider involved: a note that becomes
 *   locked, trashed, emptied or deleted loses its vectors in the
 *   same pass that would otherwise embed it, so a plaintext-
 *   derived vector never outlives the state that made its note
 *   unembeddable. Search results are re-checked against the live
 *   note rows before surfacing, as a second line of defence.
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

    /** The notes the index should hold vectors for right now
     *  (active account's database only). */
    private suspend fun desiredNotes(): Map<String, DesiredNote> {
        val all = databaseProvider.current.noteDao().getAll()
        val out = LinkedHashMap<String, DesiredNote>()
        for (note in all) {
            if (note.locked || note.trashedAt != null) continue
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
     * Full (re)build: wipes the stored index first — a rebuild
     * never mixes vectors from a previous space — then embeds
     * every eligible note. Progress is persisted in groups, so a
     * provider failure mid-build leaves the notes embedded so
     * far in place and the exception in the caller's hands
     * (Settings surfaces it); [reconcile] is the silent repair.
     */
    suspend fun rebuild(accountId: String) {
        val config = settings.activeEmbeddingConfig(accountId)
            ?: throw AiException.noKey()
        if (accountId != databaseProvider.accountId.value) return
        mutex.withLock {
            store.delete(accountId)
            val working = LinkedHashMap<String, StoredNoteVectors>()
            var dims = 0
            var sinceSave = 0
            for (desired in desiredNotes().values) {
                val vectors = embedNote(config, desired, dims)
                if (dims == 0) dims = vectors.first().size
                working[desired.note.id] = StoredNoteVectors(desired.fingerprint, vectors)
                if (++sinceSave >= SAVE_EVERY_NOTES) {
                    store.save(accountId, StoredSemanticIndex(config.spaceSignature(), dims, working))
                    sinceSave = 0
                }
            }
            if (working.isEmpty()) {
                store.delete(accountId)
            } else {
                store.save(accountId, StoredSemanticIndex(config.spaceSignature(), dims, working))
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

        // Purge: gone, locked, trashed or emptied notes lose their
        // vectors — locally, before any provider call is made.
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
        for ((noteId, want) in desired) {
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
        // vector can never resurrect a locked/trashed/deleted
        // note into the results.
        val noteDao = databaseProvider.current.noteDao()
        return raw.filter { hit ->
            val note = noteDao.getById(hit.noteId)
            note != null && !note.locked && note.trashedAt == null
        }
    }

    /** Drops the account's whole index (sign-out — like its keys,
     *  an account's vectors belong to the sign-in). */
    suspend fun clearAccount(accountId: String) {
        mutex.withLock { store.delete(accountId) }
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
