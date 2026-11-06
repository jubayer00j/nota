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
import kotlinx.coroutines.flow.flatMapLatest
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
        // Phase 3c sync triggers (spec §4.3): a cycle shortly after
        // process start when sync mode is on (the own-echo skip makes
        // an up-to-date cycle cheap), plus an edit-debounced trigger —
        // note-list emissions are re-debounced at 15 s, so a burst of
        // typing produces ONE cycle, mirroring the 1.x arrangement.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val koin = org.koin.core.context.GlobalContext.get()
                val preferences = koin.get<app.nota.data.preferences.PreferencesRepository>()
                if (preferences.driveBackupModeNow() ==
                    app.nota.data.preferences.PreferencesRepository.DRIVE_MODE_SYNC
                ) {
                    koin.get<app.nota.data.drive.DriveRepository>().startSync()
                }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val koin = org.koin.core.context.GlobalContext.get()
                val provider = koin.get<DatabaseProvider>()
                val preferences = koin.get<app.nota.data.preferences.PreferencesRepository>()
                val drive = koin.get<app.nota.data.drive.DriveRepository>()
                var debounceJob: kotlinx.coroutines.Job? = null
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                provider.accountId
                    .flatMapLatest {
                        // `current` is re-read at switch time, so the
                        // observed list is always the ACTIVE account's.
                        provider.current.noteDao().observeActive()
                    }
                    .collect {
                        // The emission is only a change signal; the
                        // cycle itself re-reads everything.
                        debounceJob?.cancel()
                        debounceJob = CoroutineScope(Dispatchers.IO).launch {
                            kotlinx.coroutines.delay(15_000)
                            runCatching {
                                if (preferences.driveBackupModeNow() ==
                                    app.nota.data.preferences.PreferencesRepository.DRIVE_MODE_SYNC
                                ) {
                                    drive.startSync()
                                }
                            }
                        }
                    }
            }
        }
        // Phase 5.1: reminders. Create the notification channel,
        // reconcile the device-global alarm set with the active
        // account's tables at process start (covers alarms lost to a
        // reboot the boot receiver missed and any drift while the
        // process was dead), then keep reconciling on every
        // reminder/note change and account switch via the
        // ReminderReconciler's table observers.
        runCatching {
            app.nota.data.reminders.ReminderNotifications.ensureChannel(this@NotaApplication)
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val koin = org.koin.core.context.GlobalContext.get()
                val provider = koin.get<DatabaseProvider>()
                val scheduler = koin.get<app.nota.data.reminders.ReminderScheduler>()
                scheduler.rescheduleAll()
                app.nota.data.reminders.ReminderReconciler.start(
                    CoroutineScope(Dispatchers.IO), provider, scheduler,
                )
            }
        }
        // Phase 4.2: semantic-index maintenance on the same
        // post-edit rhythm — note-list emissions are re-debounced
        // at 15 s, then the index reconciles: vectors for locked,
        // trashed or deleted notes are purged locally and new or
        // changed notes are embedded when a key is configured.
        // Best-effort by design (reconcile never throws); it never
        // blocks or fails a note save.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val koin = org.koin.core.context.GlobalContext.get()
                val provider = koin.get<DatabaseProvider>()
                val indexer = koin.get<app.nota.data.ai.SemanticIndexer>()
                var debounceJob: kotlinx.coroutines.Job? = null
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                provider.accountId
                    .flatMapLatest {
                        provider.current.noteDao().observeActive()
                    }
                    .collect {
                        debounceJob?.cancel()
                        debounceJob = CoroutineScope(Dispatchers.IO).launch {
                            kotlinx.coroutines.delay(15_000)
                            indexer.reconcile(provider.accountId.value)
                        }
                    }
            }
        }
        // Phase 5.3: keep the home-screen widget in step — the same
        // account-keyed observation shape as the sync trigger above,
        // over BOTH tables the glance reads (notes + reminders) and
        // the App Lock settings (a lock toggle must re-render the
        // widget immediately: content appears/disappears with the
        // posture). Emissions are re-debounced at 1 s — a burst of
        // typing produces one re-render — and the refresh itself is
        // failure-proof (WidgetRefresher swallows everything; the
        // provider re-reads the tables when it renders).
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val koin = org.koin.core.context.GlobalContext.get()
                val provider = koin.get<DatabaseProvider>()
                val lockManager = koin.get<app.nota.data.security.AppLockManager>()
                var debounceJob: kotlinx.coroutines.Job? = null
                @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
                provider.accountId
                    .flatMapLatest {
                        kotlinx.coroutines.flow.combine(
                            provider.current.noteDao().observeActive(),
                            provider.current.reminderDao().observeEnabled(),
                            lockManager.settings,
                        ) { _, _, _ -> Unit }
                    }
                    .collect {
                        debounceJob?.cancel()
                        debounceJob = CoroutineScope(Dispatchers.IO).launch {
                            kotlinx.coroutines.delay(1_000)
                            app.nota.feature.widget.WidgetRefresher
                                .requestUpdate(this@NotaApplication)
                        }
                    }
            }
        }
    }
}
