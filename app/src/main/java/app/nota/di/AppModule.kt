package app.nota.di

import app.nota.data.ai.AiHttpClient
import app.nota.data.ai.AiKeyCipher
import app.nota.data.ai.AiService
import app.nota.data.ai.AiSettingsStore
import app.nota.data.ai.AndroidKeystoreAiKeyCipher
import app.nota.data.ai.OkHttpAiClient
import app.nota.data.attachments.AttachmentStore
import app.nota.data.auth.AuthManager
import app.nota.data.auth.GuestLibraryTransfer
import app.nota.data.backup.BackupRepository
import app.nota.data.db.DatabaseProvider
import app.nota.data.db.NotaDatabase
import app.nota.data.preferences.PreferencesRepository
import app.nota.data.security.AndroidKeystoreAppLockKeyProvider
import app.nota.data.security.AndroidKeystoreNoteLockKeyProvider
import app.nota.data.security.AppLockBiometricStore
import app.nota.data.security.AppLockDeviceKeyProvider
import app.nota.data.security.AppLockManager
import app.nota.data.security.NoteLockBiometricStore
import app.nota.data.security.NoteLockDeviceKeyProvider
import app.nota.data.repository.LibraryRepository
import app.nota.data.repository.NoteLockRepository
import app.nota.data.repository.NoteRepository
import app.nota.data.templates.TemplateRepository
import app.nota.feature.ask.AskViewModel
import app.nota.feature.editor.PlainNoteEditorViewModel
import app.nota.feature.editor.RichNoteEditorViewModel
import app.nota.feature.graph.LinkGraphViewModel
import app.nota.feature.notes.NotesViewModel
import app.nota.feature.search.SearchViewModel
import app.nota.feature.templates.TemplatesViewModel
import app.nota.feature.today.TodayViewModel
import com.google.firebase.auth.FirebaseAuth
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The account the process should open on (Phase 3a): Firebase Auth
 * persists its session across restarts, so a signed-in user cold-
 * starts straight into their own database — never a flash of the
 * guest library. Falls back to guest when Firebase has no session
 * (or is unavailable in a broken build).
 */
private fun seededAccountId(): String =
    runCatching { FirebaseAuth.getInstance().currentUser?.uid }
        .getOrNull() ?: NotaDatabase.GUEST_ACCOUNT

/**
 * Koin wiring (spec §2 — Koin over Hilt for a solo project).
 * Phase 3a: the whole data layer hangs off [DatabaseProvider] — the
 * active account's database — instead of a single guest database.
 * Repositories resolve DAOs through it per call, so an account
 * switch needs no graph rebuild.
 */
val appModule = module {
    single { DatabaseProvider(androidContext(), initialAccountId = seededAccountId()) }
    single { NoteRepository(get()) }
    single { LibraryRepository(get()) }
    single { AttachmentStore(androidContext(), get()) }
    // Phase 3.6 (G2): the durable picker-insert record (filesDir).
    single { app.nota.data.attachments.PendingInsertStore(androidContext()) }
    single { TemplateRepository(androidContext()) }
    single { PreferencesRepository(androidContext()) }
    // Phase 2.5: App Lock biometric is crypto-bound - the manager owns
    // a sealed-token store over an auth-required Keystore key.
    single<AppLockDeviceKeyProvider> { AndroidKeystoreAppLockKeyProvider() }
    single { AppLockBiometricStore(get(), get()) }
    single { AppLockManager(get(), get()) }
    // Phase 2b per-note lock: Keystore device-key provider + biometric
    // copy store + the lock repository (Koin singles).
    single<NoteLockDeviceKeyProvider> { AndroidKeystoreNoteLockKeyProvider() }
    single { NoteLockBiometricStore(get(), get(), get()) }
    // The fifth get() is the SemanticIndexer: locking a note
    // evicts its semantic vectors immediately (Phase 4.2 fix
    // round). Koin resolves it lazily and the indexer does not
    // depend back on this repository, so there is no cycle even
    // though the indexer's own definition sits further down.
    single { NoteLockRepository(get(), get(), get(), get(), get()) }
    // Phase 2c backup/restore engine (local ZIP + NOMAENC1 + crossover).
    single { BackupRepository(androidContext(), get(), get(), get()) }
    // Phase 4.1 BYOK AI: Keystore-sealed per-account provider keys
    // (device-local preferences — never Room, never a backup), the
    // HTTP seam, and the call orchestration. Registered before
    // AuthManager, which drops an account's keys on sign-out.
    single<AiKeyCipher> { AndroidKeystoreAiKeyCipher() }
    single { AiSettingsStore(get(), get()) }
    single<AiHttpClient> { OkHttpAiClient() }
    single {
        AiService(get(), get(), accountIdProvider = { get<DatabaseProvider>().accountId.value })
    }
    // Phase 4.2 semantic search: the vector store is a device-
    // local filesDir directory of its own (never Room, never the
    // attachment store, so no backup can ever read it), and the
    // indexer orchestrates build/reconcile/query over it.
    // Registered before AuthManager, which drops an account's
    // vectors on sign-out alongside its keys.
    single {
        app.nota.data.ai.SemanticIndexStore(
            java.io.File(androidContext().filesDir, "semantic-index"),
        )
    }
    single { app.nota.data.ai.SemanticIndexer(get(), get(), get(), get()) }
    // Phase 3a accounts: identity (Firebase Auth, identity only) +
    // the guest→account library copy.
    single { GuestLibraryTransfer(get(), get(), get()) }
    single { AuthManager(get(), get(), get(), get(), get()) }
    // Phase 3b Google Drive: authorization (drive.file only), the
    // REST gateway, and the app-scoped operations coordinator.
    single { app.nota.data.drive.DriveAuth(get()) }
    single<app.nota.data.drive.DriveGateway> { app.nota.data.drive.DriveClient() }
    single { app.nota.data.sync.SyncApplier(get(), get(), get(), get()) }
    single {
        app.nota.data.drive.DriveRepository(
            context = get(),
            sessionProvider = { get<AuthManager>().session.value },
            driveAuth = get(),
            gateway = get(),
            backups = get(),
            prefs = get(),
            pendingRestore = app.nota.data.backup.PendingRestoreStore.shared,
            syncApplier = get(),
            provider = get(),
        )
    }
    // Phase 5.1 Reminders: the repository over the shipped
    // ReminderEntity, the alarm gateway (AlarmManager + the persisted
    // armed-id set), the scheduler, the fire handler and the
    // notification-tap store. The reconciler is started from
    // NotaApplication; receivers resolve the scheduler/handler here.
    single { app.nota.data.reminders.ReminderRepository(get()) }
    single<app.nota.data.reminders.ReminderAlarmGateway> {
        app.nota.data.reminders.AndroidReminderAlarmGateway(androidContext())
    }
    single { app.nota.data.reminders.ReminderScheduler(get(), get()) }
    single {
        app.nota.data.reminders.ReminderFireHandler(androidContext(), get(), get(), get())
    }
    single { app.nota.data.reminders.PendingNoteOpenStore() }
    // Phase 5.2 quick capture: the durable capture queue (filesDir),
    // the intake that stages door/share intents into it, and the
    // deliverer the nav shell consumes the queue with.
    single { app.nota.data.capture.PendingCaptureStore(androidContext()) }
    single { app.nota.data.capture.CaptureIntake(androidContext(), get()) }
    single { app.nota.feature.capture.CaptureDeliverer(get(), get(), get()) }
    viewModel { NotesViewModel(get(), get(), get(), get(), get()) }
    viewModel { PlainNoteEditorViewModel(get()) }
    viewModel { RichNoteEditorViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { app.nota.feature.reminders.RemindersViewModel(get(), get(), androidContext()) }
    viewModel { TemplatesViewModel(get(), get()) }
    viewModel { TodayViewModel(get(), get()) }
    viewModel {
        SearchViewModel(
            get(),
            get(),
            accountIdProvider = { get<DatabaseProvider>().accountId.value },
        )
    }
    // Phase 4.3 Ask Nota: the thread's orchestration over the
    // 4.1 service seam and the 4.2 indexer. The account flow
    // itself (not a snapshot lambda) so the ViewModel can key
    // its thread to the active identity and clear it on a
    // switch (PR #25 fix round).
    viewModel {
        AskViewModel(
            get(),
            get(),
            accountIdFlow = get<DatabaseProvider>().accountId,
        )
    }
    // Phase 4.4: the link graph map over the repository's
    // backlink observation seams.
    viewModel { LinkGraphViewModel(get()) }
}
