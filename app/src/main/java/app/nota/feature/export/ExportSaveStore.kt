package app.nota.feature.export

import android.content.Intent

/**
 * Per-note Export #1: the hand-off slot for the Save flow's
 * system file picker, held outside every composition and
 * ViewModel.
 *
 * Why: MainActivity registers the picker launcher once, at
 * activity level, because App Lock can relock while the picker
 * is open — the lock gate stops composing the nav shell
 * entirely, which disposes an editor-owned
 * rememberLauncherForActivityResult registration (and can
 * clear its ViewModel) before the result returns. A pending
 * result under a disposed registration is orphaned by the
 * registry, so the save silently never happened. The
 * activity-level registration survives the lock; this slot
 * carries the built artifact and the launch call across the
 * same boundary. In-memory only, like the staging it
 * replaces: a process death mid-picker drops the pending save.
 */
object ExportSaveStore {

    @Volatile
    private var pending: NoteExportArtifact? = null

    /** Installed by MainActivity once its launcher exists. */
    @Volatile
    var launcher: ((Intent) -> Unit)? = null

    fun stage(artifact: NoteExportArtifact) {
        pending = artifact
    }

    /** Removes and returns the staged artifact, if any. */
    fun take(): NoteExportArtifact? {
        val artifact = pending
        pending = null
        return artifact
    }

    /**
     * Hands [intent] to the activity-level launcher; false
     * when none is installed (no live activity), in which case
     * the caller unstages and tells the user.
     */
    fun launch(intent: Intent): Boolean {
        val installed = launcher ?: return false
        installed(intent)
        return true
    }
}
