package app.nota

import android.app.Application
import app.nota.data.attachments.AttachmentStore
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
        // Attachment orphan reconciliation (spec §7): sweep files no Room
        // row points at and drop rows whose file vanished. Fire-and-forget
        // on IO at process start; the store itself is idempotent.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val koin = org.koin.core.context.GlobalContext.get()
                val repository = koin.get<NoteRepository>()
                val store = koin.get<AttachmentStore>()
                // Provider form: the snapshot is taken inside the
                // store's file mutex, not before it (Codex re-review N3).
                store.reconcile { repository.observeAttachments().first() }
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
    }
}
