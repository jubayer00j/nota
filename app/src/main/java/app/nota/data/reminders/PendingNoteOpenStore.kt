package app.nota.data.reminders

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 5.1: a notification tap's request to open a note, held until
 * the nav shell can honour it. The tap lands on MainActivity (possibly
 * behind App Lock, possibly before the shell is composed), so the
 * intent extra is parked here; the shell's resumer consumes it once
 * the app is unlocked — the same durable-handoff shape as
 * PendingInsertStore.
 *
 * The record carries the account that was active when the tap was
 * handled: a request is only ever honoured against that account's
 * library (the shell is keyed per account and retires records from
 * other accounts, exactly like the picker-insert resumer).
 */
class PendingNoteOpenStore {

    data class Record(val noteId: String, val accountId: String)

    private val _record = MutableStateFlow<Record?>(null)
    val record: StateFlow<Record?> = _record

    fun request(noteId: String, accountId: String) {
        _record.value = Record(noteId, accountId)
    }

    fun clearIf(noteId: String) {
        if (_record.value?.noteId == noteId) {
            _record.value = null
        }
    }

    companion object {
        /** Intent extra carrying the tapped note's id into MainActivity. */
        const val EXTRA_NOTE_ID = "app.nota.extra.OPEN_NOTE_ID"
    }
}
