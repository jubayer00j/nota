package app.nota

import android.app.Application
import app.nota.data.attachments.AttachmentStore
import app.nota.data.auth.AuthManager
import app.nota.data.db.DatabaseProvider
import app.nota.data.repository.NoteRepository
import app.nota.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class NotaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Guarded: Robolectric re-creates the Application per test method,
        // and a second startKoin in the same process would throw
        // KoinApplicationAlreadyStartedException. In production onCreate
        // runs once per process, so the guard is a no-op there.
        if (org.koin.core.context.GlobalContext.getOrNull() == null) {
            startKoin {
                androidContext(this@NotaApplication)
                modules(appModule)
            }
        }
        // Phase 3a: settle cold-start identity before anything renders
        // — the AuthManager aligns its session state and the active
        // database with Firebase's persisted user (the provider was
        // already seeded with that uid in the DI graph; initialise()
        // is the belt-and-braces alignment + listener registration).
        runCatching {
            org.koin.core.context.GlobalContext.get()
                .get<AuthManager>().initialise()
        }
        // Attachment orphan reconciliation (spec §7): sweep files no Room
        // row points at and drop rows whose file vanished. Fire-and-forget
        // on IO at process start; the store itself is idempotent.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val koin = org.koin.core.context.GlobalContext.get()
                val repository = koin.get<NoteRepository>()
                val store = koin.get<AttachmentStore>()
                val provider = koin.get<DatabaseProvider>()
                // Provider form: the snapshot is taken inside the
                // store's file mutex, not before it (Codex re-review N3).
                // Phase 3a: the sweep's keep-set spans EVERY account
                // database on the device — the attachments/ root is
                // shared, and another account's binaries are not
                // orphans (see AttachmentStore.reconcile).
                store.reconcile(
                    allProvider = { repository.observeAttachments().first() },
                    extraKnownPaths = { provider.allAttachmentLocalPaths() },
                )
            }
        }
        // Backlink backfill (Codex fix G5b): the v2->v3 migration created
        // note_links empty, so pre-1c notes need one relink pass. The
        // repository call is a no-op once any link exists. Fire-and-forget
        // on IO at process start, like the reconcile above.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val koin = org.koin.core.context.GlobalContext.get()
                koin.get<NoteRepository>().backfillLinksIfNeeded()
            }
        }
        // Phase 3b: reconcile the scheduled-Drive-backup work with the
        // persisted mode (WorkManager itself persists across reboots;
        // this covers mode/interval changes made while the process
        // was dead and first-run enrolment).
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val koin = org.koin.core.context.GlobalContext.get()
                val preferences = koin.get<app.nota.data.preferences.PreferencesRepository>()
                app.nota.data.drive.DriveBackupScheduler.sync(
                    this@NotaApplication,
                    preferences.driveBackupModeNow(),
                    preferences.driveBackupIntervalHoursNow(),
                )
            }
        }
    }
}
