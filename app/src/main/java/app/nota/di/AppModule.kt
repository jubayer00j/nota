package app.nota.di

import app.nota.data.attachments.AttachmentStore
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteRepository
import app.nota.data.templates.TemplateRepository
import app.nota.feature.editor.PlainNoteEditorViewModel
import app.nota.feature.editor.RichNoteEditorViewModel
import app.nota.feature.notes.NotesViewModel
import app.nota.feature.search.SearchViewModel
import app.nota.feature.templates.TemplatesViewModel
import app.nota.feature.today.TodayViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * Koin wiring (spec §2 — Koin over Hilt for a solo project).
 * Phase 0 opens the guest database; per-account switching arrives with
 * identity in a later phase.
 */
val appModule = module {
    single { NotaDatabase.build(androidContext(), NotaDatabase.GUEST_ACCOUNT) }
    single { get<NotaDatabase>().noteDao() }
    single { get<NotaDatabase>().folderDao() }
    single { get<NotaDatabase>().tagDao() }
    single { get<NotaDatabase>().attachmentDao() }
    single { get<NotaDatabase>().reminderDao() }
    single { get<NotaDatabase>().noteVersionDao() }
    single { get<NotaDatabase>().noteFtsDao() }
    single { get<NotaDatabase>().savedSearchDao() }
    single { get<NotaDatabase>().noteLinkDao() }
    single { NoteRepository(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { LibraryRepository(get(), get(), get(), get()) }
    single { AttachmentStore(androidContext(), get()) }
    single { TemplateRepository(androidContext()) }
    single { PreferencesRepository(androidContext()) }
    viewModel { NotesViewModel(get(), get(), get()) }
    viewModel { PlainNoteEditorViewModel(get()) }
    viewModel { RichNoteEditorViewModel(get(), get(), get()) }
    viewModel { TemplatesViewModel(get(), get()) }
    viewModel { TodayViewModel(get(), get()) }
    viewModel { SearchViewModel(get()) }
}
