package app.nota.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.SemanticIndexer
import app.nota.data.db.NoteEntity
import app.nota.data.db.SavedSearchEntity
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SearchViewModel(
    private val repository: NoteRepository,
    private val semanticIndexer: SemanticIndexer? = null,
    private val aiSettingsStore: AiSettingsStore? = null,
    private val accountIdProvider: () -> String = { "" },
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<NoteEntity>>(emptyList())
    val results: StateFlow<List<NoteEntity>> = _results

    /** Phase 4.2: "Related by meaning" — semantic hits for the
     *  current query, best first, excluding anything the keyword
     *  results already show. Empty whenever semantic search can't
     *  run (no key, no index, offline, provider error): the
     *  keyword search is completely unaffected either way. */
    private val _semanticResults = MutableStateFlow<List<NoteEntity>>(emptyList())
    val semanticResults: StateFlow<List<NoteEntity>> = _semanticResults

    /** Beginner AI Setup #1 (F7): true when search-by-meaning is
     *  off SPECIFICALLY because the active provider has no saved
     *  key — the state behind Search's one quiet hint line. It is
     *  deliberately NOT "semantic search can't run" in general: a
     *  key with no resolvable embedding model (a fresh Custom
     *  slot) also can't run, but "add an API key" would be the
     *  wrong advice there, so the hint stays hidden then too. */
    private val _semanticNeedsKey = MutableStateFlow(false)
    val semanticNeedsKey: StateFlow<Boolean> = _semanticNeedsKey

    init {
        refreshSemanticHint()
    }

    /** Re-reads the key state: once at construction and again
     *  when the screen (re)composes, so returning from AI
     *  settings with a fresh key retires the hint without a
     *  restart. A missing store (bare test constructions)
     *  leaves the hint off. */
    fun refreshSemanticHint() {
        val store = aiSettingsStore ?: return
        viewModelScope.launch {
            _semanticNeedsKey.value = !store.hasUsableKey(accountIdProvider())
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
            val related = ArrayList<NoteEntity>(hits.size)
            for (hit in hits) {
                if (hit.noteId in keywordIds) continue
                val note = repository.getById(hit.noteId) ?: continue
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
