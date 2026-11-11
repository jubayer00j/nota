package app.nota.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.NoteChunker
import app.nota.data.ai.SemanticHit
import app.nota.data.ai.SemanticIndexer
import app.nota.data.db.SavedSearchEntity
import app.nota.data.repository.NoteRepository
import app.nota.data.repository.NoteSearch
import app.nota.data.repository.NoteSearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which "search by meaning is off" guidance Search shows, if
 *  any — see [SearchViewModel.semanticHint]. */
enum class SemanticHint { OFF, NEEDS_KEY, NEEDS_SETUP }

class SearchViewModel(
    private val repository: NoteRepository,
    private val semanticIndexer: SemanticIndexer? = null,
    private val aiSettingsStore: AiSettingsStore? = null,
    private val accountIdProvider: () -> String = { "" },
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** The ONE ranked result list (Search Quality Pass): keyword
     *  results first, re-ranked in place when the meaning pass
     *  lands — it boosts keyword hits that also match by meaning
     *  and interleaves meaning-only notes (flagged
     *  [NoteSearchResult.byMeaning]), instead of the Phase 4.2
     *  separate "Related by meaning" section. Meaning results
     *  simply never arrive when semantic search can't run (no
     *  key, no index, offline, provider error): the keyword
     *  search is completely unaffected either way. */
    private val _results = MutableStateFlow<List<NoteSearchResult>>(emptyList())
    val results: StateFlow<List<NoteSearchResult>> = _results

    /** Beginner AI Setup #1 (F7), made config-aware in fix
     *  round 2 (Codex P2) and endpoint-aware in fix round 3:
     *  Search's one quiet hint line, driven by whether an
     *  embedding config for the active account can actually
     *  SERVE a call — resolving in the store
     *  ([AiSettingsStore.activeEmbeddingConfig]) plus a usable
     *  endpoint ([app.nota.data.ai.AiEmbeddingConfig.hasUsableEndpoint]):
     *  - OFF: a config resolves and its endpoint can serve the
     *    call; semantic search can run and there is nothing
     *    to say.
     *  - NEEDS_KEY: no usable key and the active provider is a
     *    preset, whose endpoint and embedding model come
     *    pre-filled — a key alone genuinely completes its
     *    setup, so this stage may promise exactly that.
     *  - NEEDS_SETUP: every other incomplete state — Custom
     *    with no key yet (a key alone never finishes Custom,
     *    which also needs its endpoint and an embedding model),
     *    any provider holding a key whose embedding config
     *    still doesn't resolve, and a Custom config that
     *    resolves on paper (key + embedding model) while its
     *    endpoint is blank or invalid, so every embedding call
     *    from it would fail NOT_CONFIGURED. Its copy promises
     *    only that finishing setup switches the feature on.
     *  The first form of this state keyed on `!hasUsableKey`
     *  alone, so saving just a key on Custom retired the hint
     *  while semantic search still couldn't run — the guidance
     *  disappeared before the feature worked. */
    private val _semanticHint = MutableStateFlow(SemanticHint.OFF)
    val semanticHint: StateFlow<SemanticHint> = _semanticHint

    init {
        refreshSemanticHint()
    }

    /** Re-reads the setup state: once at construction and again
     *  when the screen (re)composes, so returning from AI
     *  settings with setup finished retires the hint without a
     *  restart. A missing store (bare test constructions)
     *  leaves the hint off. */
    fun refreshSemanticHint() {
        val store = aiSettingsStore ?: return
        viewModelScope.launch {
            val account = accountIdProvider()
            val config = store.activeEmbeddingConfig(account)
            _semanticHint.value = when {
                config != null && config.hasUsableEndpoint() ->
                    SemanticHint.OFF
                !store.hasUsableKey(account) &&
                    store.activeProvider(account) != AiProviderId.CUSTOM ->
                    SemanticHint.NEEDS_KEY
                else -> SemanticHint.NEEDS_SETUP
            }
        }
    }

    private var keywordJob: Job? = null
    private var semanticJob: Job? = null

    val savedSearches: StateFlow<List<SavedSearchEntity>> = repository.observeSavedSearches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
        // Cancel any in-flight search: a slower older query must never
        // overwrite the results of a newer one (Codex F5). The current-
        // query guards below are a second line of defence if a search
        // completes despite cancellation.
        keywordJob?.cancel()
        semanticJob?.cancel()
        if (value.isBlank()) {
            _results.value = emptyList()
            return
        }
        // Keyword pass (Search Quality Pass): NO debounce. The
        // ranked FTS pass is a local indexed query and 1.x's
        // keyword search answers per keystroke; the old flat
        // 200 ms delay was pure added latency in front of it.
        // Cancellation above remains the ordering guard.
        val keywordResults = kotlinx.coroutines.CompletableDeferred<List<NoteSearchResult>>()
        keywordJob = viewModelScope.launch {
            val found = repository.searchNotes(value)
            keywordResults.complete(found)
            if (_query.value == value) _results.value = found
        }
        // Meaning pass: it costs a provider call, so it keeps a
        // debounce of its own (1.x: 300 ms) and lands after the
        // keyword results are on screen, BLENDING into the same
        // list (NoteSearch.blend, 1.x's mergeSemanticResults)
        // instead of trailing it as a separate section. Any
        // failure degrades to "keyword results stand", silently.
        semanticJob = viewModelScope.launch {
            delay(SEMANTIC_DEBOUNCE_MS)
            val indexer = semanticIndexer ?: return@launch
            // Blend against THIS query's keyword results,
            // handed over directly by the sibling job — never
            // whatever an older query left in the state flow,
            // and if that pass failed there is nothing honest
            // to blend into, so the meaning pass stands down.
            val keyword = try {
                keywordResults.await()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return@launch
            }
            if (_query.value != value) return@launch
            val hits = try {
                indexer.search(accountIdProvider(), value)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
            if (_query.value != value || hits.isEmpty()) return@launch
            val floor = indexer.similarityFloor(accountIdProvider())
            val keywordIds = keyword.mapTo(HashSet()) { it.id }
            val updatedAtById = HashMap<String, Long>()
            for (result in keyword) updatedAtById[result.id] = result.row.updatedAt
            // Meaning-only rows, fetched live: a stale vector
            // must never surface a note that no longer qualifies
            // (the indexer re-checks eligibility too).
            val meaningRows = HashMap<String, NoteSearchResult>()
            for (hit in hits) {
                if (hit.noteId in keywordIds) continue
                // Phase 6.1: the row carries every flag this
                // check reads (locked/trashed/archived) plus
                // everything the result card renders.
                val row = repository.getRowById(hit.noteId) ?: continue
                // Archived notes stay out of search everywhere —
                // keyword results above already exclude them via
                // FTS; the meaning pass must agree (Jules
                // PR #23 P2).
                if (row.locked || row.trashedAt != null || row.archived) continue
                updatedAtById[row.id] = row.updatedAt
                meaningRows[row.id] = NoteSearchResult(
                    row = row,
                    score = 0.0,
                    snippet = passageSnippet(hit),
                    byMeaning = true,
                )
            }
            val blended = NoteSearch.blend(
                keyword = keyword.map {
                    NoteSearch.KeywordScore(it.id, it.score.toInt(), it.row.updatedAt)
                },
                similarities = hits.associate { it.noteId to it.score },
                floor = floor,
                updatedAtById = updatedAtById,
            )
            if (_query.value != value) return@launch
            val keywordById = keyword.associateBy { it.id }
            _results.value = blended.mapNotNull { entry ->
                val base = keywordById[entry.noteId] ?: meaningRows[entry.noteId]
                    ?: return@mapNotNull null
                base.copy(score = entry.score, byMeaning = entry.byMeaning)
            }
        }
    }

    /**
     * The snippet for a meaning-only result: the text of the
     * chunk that scored best for the query — the passage that
     * actually matched — re-derived by re-chunking the note's
     * current text (the index stores vectors, not text; Ask
     * relies on the same reproduction). Chunk 0 opens with the
     * note's title, which the card already shows, so the
     * passage starts after it.
     */
    private suspend fun passageSnippet(hit: SemanticHit): String {
        val note = repository.getById(hit.noteId) ?: return ""
        val chunks = NoteChunker.chunk(note.title, note.plainText)
        val chunk = chunks.getOrNull(hit.bestChunkIndex) ?: return ""
        val firstLine = chunk.lineSequence().firstOrNull() ?: return chunk
        val passage = if (note.title.isNotBlank() && firstLine.trim() == note.title.trim()) {
            chunk.substring(firstLine.length).trimStart('\n')
        } else {
            chunk
        }
        return passage.ifBlank { chunk }
    }

    fun applySaved(search: SavedSearchEntity) = setQuery(search.query)

    fun saveCurrentSearch(name: String) {
        val q = _query.value.trim()
        if (q.isEmpty() || name.isBlank()) return
        viewModelScope.launch { repository.saveSearch(name.trim(), q) }
    }

    fun deleteSaved(search: SavedSearchEntity) {
        viewModelScope.launch { repository.deleteSavedSearch(search) }
    }

    private companion object {
        /** The meaning pass's own debounce (1.x's semantic
         *  debounce): long enough that fast typing doesn't burn
         *  a provider call per keystroke, short enough that the
         *  blend lands as part of the same search gesture. */
        const val SEMANTIC_DEBOUNCE_MS = 300L
    }
}
