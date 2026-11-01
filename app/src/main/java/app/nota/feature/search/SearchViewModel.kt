package app.nota.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _results = MutableStateFlow<List<NoteEntity>>(emptyList())
    val results: StateFlow<List<NoteEntity>> = _results

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
            return
        }
        searchJob = viewModelScope.launch {
            // Small debounce: coalesce rapid keystrokes into one search.
            delay(200)
            val found = repository.searchNotes(value)
            if (_query.value == value) _results.value = found
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
