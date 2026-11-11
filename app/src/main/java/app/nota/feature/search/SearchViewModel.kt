package app.nota.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.SemanticIndexer
import app.nota.data.db.NoteListRow
import app.nota.data.db.SavedSearchEntity
import app.nota.data.repository.NoteRepository
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

    private val _results = MutableStateFlow<List<NoteListRow>>(emptyList())
    val results: StateFlow<List<NoteListRow>> = _results

    /** Phase 4.2: "Related by meaning" — semantic hits for the
     *  current query, best first, excluding anything the keyword
     *  results already show. Empty whenever semantic search can't
     *  run (no key, no index, offline, provider error): the
     *  keyword search is completely unaffected either way. */
    private val _semanticResults = MutableStateFlow<List<NoteListRow>>(emptyList())
    val semanticResults: StateFlow<List<NoteListRow>> = _semanticResults

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

    private var searchJob: Job? = null

    val savedSearches: StateFlow<List<SavedSearchEntity>> = repository.observeSavedSearches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
        // Cancel any in-flight search: a slower older query must never
        // overwrite the results of a newer one (Codex F5). The current-
        // query guard below is a second line of defence if a search
        // completes despite cancellation.
        searchJob?.cancel()
        if (value.isBlank()) {
            _results.value = emptyList()
            _semanticResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            // Small debounce: coalesce rapid keystrokes into one search.
            delay(200)
            val found = repository.searchNotes(value)
            if (_query.value == value) _results.value = found
            // Semantic pass for the same (still-current) query. It
            // costs a provider call, so it runs after the keyword
            // results are already on screen; any failure degrades
            // to "no related section", silently.
            _semanticResults.value = emptyList()
            val indexer = semanticIndexer ?: return@launch
            val hits = try {
                indexer.search(accountIdProvider(), value)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
            if (_query.value != value) return@launch
            val keywordIds = found.mapTo(HashSet()) { it.id }
            val related = ArrayList<NoteListRow>(hits.size)
            for (hit in hits) {
                if (hit.noteId in keywordIds) continue
                // Phase 6.1: the row carries every flag this check
                // reads (locked/trashed/archived) plus everything the
                // result card renders — no full-entity fetch per hit.
                val note = repository.getRowById(hit.noteId) ?: continue
                // Archived notes stay out of search everywhere —
                // keyword results above already exclude them via
                // FTS; the semantic section must agree (Jules
                // PR #23 P2).
                if (note.locked || note.trashedAt != null || note.archived) continue
                related += note
            }
            if (_query.value == value) _semanticResults.value = related
        }
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
}
