package app.nota.data.backup

import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pending-restore state logic (Phase 2.5, F2) — plain JVM tests.
 *
 * The store is built on a [Dispatchers.Unconfined] scope so every
 * launched stage transition completes synchronously inside the call
 * that triggered it: what is asserted here is the state machine, not
 * scheduling. The "lock cycle" in these tests is exactly what a lock
 * cycle is to the store — nothing touches it while BackupSection's
 * composition is destroyed and recreated — so the assertions prove
 * the staged intent survives untouched and resumes on claim.
 */
class PendingRestoreStoreTest {

    private fun newStore() = PendingRestoreStore(CoroutineScope(Dispatchers.Unconfined))

    private fun fakeHandle(stagingDir: File): BackupReader.Handle = BackupReader.Handle(
        manifest = BackupManifest(
            backupFormatVersion = 2,
            ownerId = null,
            libraryId = null,
            nomaVersion = "0.3.0",
            createdAt = "2026-11-02T00:00:00Z",
            noteCount = 1,
            folderCount = 0,
            tagCount = 0,
            attachmentCount = 0,
            reminderCount = 0,
            deviceId = null,
        ),
        notes = emptyList(),
        folders = emptyList(),
        tags = emptyList(),
        reminders = emptyList(),
        attachments = emptyList(),
        tombstones = emptyList(),
        deviceId = null,
        encrypted = false,
        stagingDir = stagingDir,
        decryptedZip = null,
    )

    @Test
    fun stageSurvivesLockCycleAndResumesToCompletion() {
        val store = newStore()
        val temp = File.createTempFile("pending-restore", ".bin")
        val staging = File.createTempFile("pending-staging", "").apply { delete(); mkdirs() }

        store.stageCopy { temp }
        // Lock cycle: the settings composition is destroyed and later
        // recreated; the store is not touched. The intent must be
        // exactly where the picker left it.
        val staged = store.state.value
        assertTrue(staged is PendingRestoreState.NeedsRead)
        assertEquals(temp.absolutePath, (staged as PendingRestoreState.NeedsRead).tempFilePath)
        assertTrue(temp.exists())

        // After unlock, the fresh composition claims the read.
        store.performRead { fakeHandle(staging) }
        assertTrue(store.state.value is PendingRestoreState.SummaryReady)
        assertEquals(1, store.summaryHandle?.manifest?.noteCount)

        store.performApply { "Restored (merge): 1 added, 0 updated, 0 deleted." }
        val done = store.state.value
        assertTrue(done is PendingRestoreState.Completed)
        assertEquals(
            "Restored (merge): 1 added, 0 updated, 0 deleted.",
            (done as PendingRestoreState.Completed).message,
        )
        // Terminal cleanup still happened: temp copy + staging gone.
        assertFalse(temp.exists())
        assertFalse(staging.exists())

        store.acknowledge()
        assertEquals(PendingRestoreState.Idle, store.state.value)
    }

    @Test
    fun copyFailureIsTypedOutcomeNotSilence() {
        val store = newStore()
        store.stageCopy { throw IllegalStateException("Could not open the chosen file.") }
        val failed = store.state.value
        assertTrue(failed is PendingRestoreState.Failed)
        assertEquals("Could not open the chosen file.", (failed as PendingRestoreState.Failed).message)
    }

    @Test
    fun encryptedArchiveReasksPassphraseVisiblyAndKeepsFile() {
        val store = newStore()
        val temp = File.createTempFile("pending-restore-enc", ".bin")
        val staging = File.createTempFile("pending-staging-enc", "").apply { delete(); mkdirs() }
        store.stageCopy { temp }

        // No passphrase yet → prompt stage, file kept.
        store.performRead { throw BackupKeyNeededError("Backup is encrypted") }
        var stage = store.state.value
        assertTrue(stage is PendingRestoreState.NeedsPassphrase)
        assertEquals(null, (stage as PendingRestoreState.NeedsPassphrase).error)
        assertTrue(temp.exists())

        // Wrong passphrase → back at the prompt WITH the error visible,
        // file still kept: re-asked, never silently dropped.
        store.performRead { throw BackupUnlockError("That passphrase didn't open the backup.") }
        stage = store.state.value
        assertTrue(stage is PendingRestoreState.NeedsPassphrase)
        assertEquals(
            "That passphrase didn't open the backup.",
            (stage as PendingRestoreState.NeedsPassphrase).error,
        )
        assertTrue(temp.exists())

        // Right passphrase → summary.
        store.performRead { fakeHandle(staging) }
        assertTrue(store.state.value is PendingRestoreState.SummaryReady)
        store.cancel()
        assertEquals(PendingRestoreState.Idle, store.state.value)
        assertFalse(temp.exists())
    }

    @Test
    fun unreadableArchiveFailsTypedAndDeletesTemp() {
        val store = newStore()
        val temp = File.createTempFile("pending-restore-bad", ".bin")
        store.stageCopy { temp }
        store.performRead { throw BackupError("Not a Noma backup file.") }
        val failed = store.state.value
        assertTrue(failed is PendingRestoreState.Failed)
        assertEquals("Not a Noma backup file.", (failed as PendingRestoreState.Failed).message)
        assertFalse(temp.exists())
    }

    @Test
    fun applyFailureIsTypedOutcomeNotSilence() {
        val store = newStore()
        val temp = File.createTempFile("pending-restore-apply", ".bin")
        val staging = File.createTempFile("pending-staging-apply", "").apply { delete(); mkdirs() }
        store.stageCopy { temp }
        store.performRead { fakeHandle(staging) }
        store.performApply { throw IllegalStateException("Restore failed: database is locked.") }
        val failed = store.state.value
        assertTrue(failed is PendingRestoreState.Failed)
        assertEquals(
            "Restore failed: database is locked.",
            (failed as PendingRestoreState.Failed).message,
        )
        assertFalse(temp.exists())
    }

    @Test
    fun duplicateClaimsNeverDoubleReadOrDoubleApply() {
        val store = newStore()
        val temp = File.createTempFile("pending-restore-dupe", ".bin")
        val staging = File.createTempFile("pending-staging-dupe", "").apply { delete(); mkdirs() }
        store.stageCopy { temp }

        var reads = 0
        store.performRead { reads++; fakeHandle(staging) }
        // Second claim while SummaryReady (recomposition re-run) is a no-op.
        store.performRead { reads++; fail("second read must not run"); fakeHandle(staging) }
        assertEquals(1, reads)
        assertTrue(store.state.value is PendingRestoreState.SummaryReady)

        var applies = 0
        store.performApply { applies++; "done" }
        store.performApply { applies++; fail("second apply must not run"); "done" }
        assertEquals(1, applies)
        assertTrue(store.state.value is PendingRestoreState.Completed)
    }

    @Test
    fun cancelAtPassphrasePromptDeletesTempAndIdles() {
        val store = newStore()
        val temp = File.createTempFile("pending-restore-cancel", ".bin")
        store.stageCopy { temp }
        store.performRead { throw BackupKeyNeededError("Backup is encrypted") }
        assertTrue(store.state.value is PendingRestoreState.NeedsPassphrase)
        store.cancel()
        assertEquals(PendingRestoreState.Idle, store.state.value)
        assertFalse(temp.exists())
    }
}
