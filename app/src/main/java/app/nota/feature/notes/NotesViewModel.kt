package app.nota.feature.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.db.NoteEntity
import app.nota.data.repository.NoteRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** Phase 0: exposes the Room-backed note stream. The DB is empty until
 *  Phase 1 adds create/edit; the screen renders design-preview cards meanwhile. */
class NotesViewModel(repository: NoteRepository) : ViewModel() {
    val notes: StateFlow<List<NoteEntity>> = repository.observeActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
