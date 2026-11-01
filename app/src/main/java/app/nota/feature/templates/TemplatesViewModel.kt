package app.nota.feature.templates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nota.data.repository.NoteRepository
import app.nota.data.templates.NoteTemplate
import app.nota.data.templates.TemplateRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Templates screen VM (Phase 1c): list, apply (creates a note), delete. */
class TemplatesViewModel(
    private val templateRepository: TemplateRepository,
    private val noteRepository: NoteRepository,
) : ViewModel() {

    val templates: StateFlow<List<NoteTemplate>> = templateRepository.templates
        .map { it ?: emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { templateRepository.load() }
    }

    fun apply(template: NoteTemplate, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val note = noteRepository.createRichNote(
                title = template.title,
                contentHtml = template.contentHtml,
            )
            onCreated(note.id)
        }
    }

    fun delete(template: NoteTemplate) {
        if (template.builtIn) return
        viewModelScope.launch { templateRepository.delete(template.id) }
    }
}
