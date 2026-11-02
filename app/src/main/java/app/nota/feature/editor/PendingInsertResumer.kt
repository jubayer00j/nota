package app.nota.feature.editor

import app.nota.data.attachments.PendingInsertRecord

/**
 * Decides when a staged picker insert should ROUTE the user back
 * into its note (Phase 3.6, G2) — the [app.nota.feature.settings.UnlockRestoreResumer]
 * pattern applied to [app.nota.data.attachments.PendingInsertStore].
 *
 * The nav shell feeds it every (locked, record, account, current
 * route) change; [onState] returns the note id to navigate to, or
 * null:
 *
 * - NEVER while locked (nothing surfaces before a successful unlock;
 *   the record predates the lock and says nothing about note content,
 *   but the routing itself is an unlocked-session act);
 * - NEVER for a record staged by another account (the shell clears
 *   those instead — the note lives in another library);
 * - NEVER when the user is already inside that note's editor (the
 *   normal fast return: the editor consumes the record itself when
 *   the picker result lands);
 * - EXACTLY ONCE per record per unlocked session: if the user backs
 *   out of the routed editor without a result arriving, the shell
 *   does not yank them back in. A NEW record (a different id) fires
 *   again immediately; a fresh unlock re-arms the same record.
 *
 * One instance lives for one unlocked session (the nav shell is only
 * composed while unlocked), which is what makes "once per unlock"
 * structural rather than timestamped.
 */
class PendingInsertResumer {

    /** Id of the record already surfaced this session. */
    private var surfacedRecordId: String? = null

    /**
     * @param currentNoteId the note id of the current route when the
     *   user is inside a note editor, else null.
     * @return the note id to route to, or null for no routing.
     */
    fun onState(
        locked: Boolean,
        record: PendingInsertRecord?,
        currentAccountId: String,
        currentNoteId: String?,
    ): String? {
        if (locked) {
            // A fresh unlock may surface the record again.
            surfacedRecordId = null
            return null
        }
        if (record == null) {
            surfacedRecordId = null
            return null
        }
        if (record.accountId != currentAccountId) return null
        if (currentNoteId == record.noteId) return null
        if (surfacedRecordId == record.id) return null
        surfacedRecordId = record.id
        return record.noteId
    }
}

/**
 * Resolves a staged record's caret target against the editor's
 * CURRENT segment list (Phase 3.6, G2). The list may have been
 * rebuilt from storage since staging (lock swap / process death), so
 * the staged index is only a hint: the anchor — the target segment's
 * plain-text prefix at stage time — is the identity.
 *
 * [candidates] pairs each segment's (isTextLike, plainText): text and
 * quote segments are the only split-insert targets. Resolution order:
 * 1. the staged index, when it is in range, text-like, and its text
 *    starts with the anchor (or the anchor is empty);
 * 2. the first text-like segment whose text starts with a non-empty
 *    anchor;
 * 3. the staged index, when in range and text-like (anchor drifted —
 *    the position is still the best information available);
 * else null (the caller falls back to the ordinary caret insert).
 */
fun resolveInsertTargetIndex(
    record: PendingInsertRecord,
    candidates: List<Pair<Boolean, String>>,
): Int? {
    val index = record.segmentIndex
    val atIndex = candidates.getOrNull(index)
    if (atIndex != null && atIndex.first &&
        (record.segmentAnchor.isEmpty() || atIndex.second.startsWith(record.segmentAnchor))
    ) {
        return index
    }
    if (record.segmentAnchor.isNotEmpty()) {
        val byAnchor = candidates.indexOfFirst {
            it.first && it.second.startsWith(record.segmentAnchor)
        }
        if (byAnchor >= 0) return byAnchor
    }
    if (atIndex != null && atIndex.first) return index
    return null
}
