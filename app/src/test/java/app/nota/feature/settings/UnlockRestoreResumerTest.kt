package app.nota.feature.settings

import app.nota.data.backup.PendingRestoreState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2.9 (J2): the unlock-resume contract for a pending restore.
 * A restore waiting at NeedsRead / NeedsPassphrase / SummaryReady
 * surfaces exactly once per unlock; nothing surfaces while locked;
 * a dismissed (still pending) restore is not re-pushed until the
 * next unlock; completed/cancelled restores never surface.
 */
class UnlockRestoreResumerTest {

    private val path = "/tmp/restore-a.zip"
    private val otherPath = "/tmp/restore-b.zip"

    @Test
    fun nothingSurfacesWhileLocked() {
        val r = UnlockRestoreResumer()
        assertFalse(r.onState(locked = true, PendingRestoreState.NeedsPassphrase(path)))
        assertFalse(r.onState(locked = true, PendingRestoreState.SummaryReady(path)))
        assertFalse(r.onState(locked = true, PendingRestoreState.NeedsRead(path)))
    }

    @Test
    fun pendingPassphraseSurfacesOnceOnUnlock() {
        val r = UnlockRestoreResumer()
        // Locked with the restore waiting: silent.
        assertFalse(r.onState(true, PendingRestoreState.NeedsPassphrase(path)))
        // The unlock surfaces it…
        assertTrue(r.onState(false, PendingRestoreState.NeedsPassphrase(path)))
        // …and only once: recompositions / repeated emissions with
        // the user now looking at it (or having walked away) do not
        // fire again.
        assertFalse(r.onState(false, PendingRestoreState.NeedsPassphrase(path)))
        assertFalse(r.onState(false, PendingRestoreState.NeedsPassphrase(path, error = "wrong")))
    }

    @Test
    fun summaryReadySurfacesOnUnlock() {
        val r = UnlockRestoreResumer()
        assertFalse(r.onState(true, PendingRestoreState.SummaryReady(path)))
        assertTrue(r.onState(false, PendingRestoreState.SummaryReady(path)))
        assertFalse(r.onState(false, PendingRestoreState.SummaryReady(path)))
    }

    @Test
    fun needsReadSurfacesSoSettingsCanClaimTheRead() {
        // The recorded 0.3.4 flow: picker return → relock → the stage
        // at unlock time is NeedsRead (BackupSection has not been
        // composed since the pick, so nothing claimed the read yet).
        // Routing to Settings is what starts the read.
        val r = UnlockRestoreResumer()
        assertTrue(r.onState(false, PendingRestoreState.NeedsRead(path)))
        // The read claims and advances the SAME restore: no re-fire
        // when the passphrase stage lands — the user is already there.
        assertFalse(r.onState(false, PendingRestoreState.Reading(path)))
        assertFalse(r.onState(false, PendingRestoreState.NeedsPassphrase(path)))
    }

    @Test
    fun transientAndTerminalStagesNeverSurface() {
        val r = UnlockRestoreResumer()
        assertFalse(r.onState(false, PendingRestoreState.Idle))
        assertFalse(r.onState(false, PendingRestoreState.Copying))
        assertFalse(r.onState(false, PendingRestoreState.Reading(path)))
        assertFalse(r.onState(false, PendingRestoreState.Applying(path)))
        assertFalse(r.onState(false, PendingRestoreState.Completed("done")))
        assertFalse(r.onState(false, PendingRestoreState.Failed("nope")))
    }

    @Test
    fun relockRearmsTheSurfaceForTheNextUnlock() {
        val r = UnlockRestoreResumer()
        assertTrue(r.onState(false, PendingRestoreState.NeedsPassphrase(path)))
        assertFalse(r.onState(false, PendingRestoreState.NeedsPassphrase(path)))
        // User walks away, app locks (shell destroyed / locked flag),
        // user unlocks again: the still-waiting restore surfaces once
        // more — once per unlock, not once ever.
        assertFalse(r.onState(true, PendingRestoreState.NeedsPassphrase(path)))
        assertTrue(r.onState(false, PendingRestoreState.NeedsPassphrase(path)))
    }

    @Test
    fun aNewRestoreSurfacesEvenWithinTheSameUnlock() {
        val r = UnlockRestoreResumer()
        assertTrue(r.onState(false, PendingRestoreState.NeedsPassphrase(path)))
        // First restore completes; a second, different restore waits.
        assertFalse(r.onState(false, PendingRestoreState.Completed("done")))
        assertFalse(r.onState(false, PendingRestoreState.Idle))
        assertTrue(r.onState(false, PendingRestoreState.SummaryReady(otherPath)))
    }

    @Test
    fun cancelledRestoreNeverSurfaces() {
        val r = UnlockRestoreResumer()
        assertTrue(r.onState(false, PendingRestoreState.NeedsPassphrase(path)))
        // Cancel at the prompt → Idle: no surface, and the marker is
        // retired so the same path picked again later still surfaces.
        assertFalse(r.onState(false, PendingRestoreState.Idle))
        assertTrue(r.onState(false, PendingRestoreState.NeedsPassphrase(path)))
    }
}
