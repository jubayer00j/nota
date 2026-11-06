package app.nota.data.reminders

import app.nota.data.db.DatabaseProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Phase 5.1: keeps the device-global alarm set reconciled with the
 * active account's tables, following the application's established
 * "observe + reconcile" idiom (the semantic indexer does the same).
 *
 * Every reminder change path writes the Room tables — local
 * set/edit/remove, note trash/restore/delete (FK cascade), backup
 * restore, sync merge, guest→account transfer — and every account
 * switch swaps the observed database, so watching (reminders × notes)
 * per account and funnelling into [ReminderScheduler.rescheduleAll]
 * covers them all through one seam. The projection is reduced to the
 * alarm-relevant signature and debounced, so ordinary note editing
 * (which touches the notes table) doesn't churn AlarmManager.
 */
object ReminderReconciler {

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    fun start(
        scope: CoroutineScope,
        provider: DatabaseProvider,
        scheduler: ReminderScheduler,
    ) {
        scope.launch {
            provider.accountId.flatMapLatest {
                val db = provider.current
                combine(
                    db.reminderDao().observeAll(),
                    db.noteDao().observeAllNotes(),
                ) { reminders, notes ->
                    val trashedNoteIds = notes.filter { it.trashedAt != null }
                        .map { it.id }
                        .toSet()
                    reminders.map { reminder ->
                        listOf(
                            reminder.id,
                            reminder.triggerAt.toString(),
                            reminder.enabled.toString(),
                            reminder.alertType,
                            reminder.noteId,
                        ).joinToString(":")
                    } + trashedNoteIds.map { "trashed:$it" }
                }
            }
                .distinctUntilChanged()
                .debounce(250)
                .map { }
                .collectLatest {
                    runCatching { scheduler.rescheduleAll() }
                }
        }
    }
}
