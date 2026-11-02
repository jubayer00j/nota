package app.nota.feature.settings

import app.nota.data.backup.PendingRestoreState

/**
 * Decides when a pending backup restore should be SURFACED to the
 * user (Phase 2.9, J2).
 *
 * Phase 2.5 made the restore itself lock-proof: the stage lives in
 * the app-scoped [app.nota.data.backup.PendingRestoreStore] and
 * BackupSection renders it — but only once the user happens to open
 * Settings. The 0.3.4 device finding: after unlocking, nothing
 * pointed at the waiting passphrase prompt; the restore sat in
 * Settings until the user went looking for it.
 *
 * This class is the missing push. The nav shell feeds it every
 * (locked, pending) pair; [onState] returns true exactly when the
 * shell should route to the restore continuation:
 *
 * - NEVER while locked (nothing may surface before a successful
 *   unlock — the lock screen stays content-free);
 * - when the app is unlocked and a restore is waiting at a stage
 *   that needs the user: [PendingRestoreState.NeedsRead] (routing
 *   to Settings is what lets BackupSection claim the read),
 *   [PendingRestoreState.NeedsPassphrase] or
 *   [PendingRestoreState.SummaryReady];
 * - EXACTLY ONCE per restore per unlock: if the user walks away
 *   from Settings without finishing (the stage is unchanged), it
 *   does not yank them back. It fires again on the NEXT unlock,
 *   and immediately for a NEW restore (a different temp file);
 * - NEVER for a completed / cancelled / failed restore.
 *
 * One instance lives for one unlocked session (the nav shell is
 * only composed while unlocked), which is what makes "once per
 * unlock" structural rather than timestamped.
 */
class UnlockRestoreResumer {

    /** Temp-file path of the restore already surfaced this session. */
    private var surfacedPath: String? = null

    fun onState(locked: Boolean, pending: PendingRestoreState): Boolean {
        if (locked) {
            // A fresh unlock may surface the restore again.
            surfacedPath = null
            return false
        }
        val waitingPath = when (pending) {
            is PendingRestoreState.NeedsRead -> pending.tempFilePath
            is PendingRestoreState.NeedsPassphrase -> pending.tempFilePath
            is PendingRestoreState.SummaryReady -> pending.tempFilePath
            else -> null
        }
        if (waitingPath == null) {
            // Terminal stages retire the surfaced marker, so a NEW
            // restore later in the same session can surface too.
            if (pending is PendingRestoreState.Idle ||
                pending is PendingRestoreState.Completed ||
                pending is PendingRestoreState.Failed
            ) {
                surfacedPath = null
            }
            return false
        }
        if (surfacedPath == waitingPath) return false
        surfacedPath = waitingPath
        return true
    }
}
