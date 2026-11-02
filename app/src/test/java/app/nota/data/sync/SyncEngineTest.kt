package app.nota.data.sync

import app.nota.data.backup.BackupManifest
import app.nota.data.backup.BackupWriter
import app.nota.data.backup.BxAttachmentMeta
import app.nota.data.backup.BxNote
import app.nota.data.backup.BxTombstone
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SyncEngine merge semantics (Phase 3c) — the 1.x three-way rules
 * pinned as pure unit tests: newest-wins, conflict copies for notes,
 * tombstone propagation + revival, absence-is-not-deletion, own echo,
 * and attachment byte conflicts cloned instead of overwritten.
 * Settings are not part of the snapshot at all (the writer carries
 * none), so "settings never sync" is structural, not a rule here.
 */
class SyncEngineTest {

    private val ids = AtomicInteger(0)

    private fun note(id: String, title: String, updatedAt: Long, content: String = "<p>$title</p>") =
        BxNote(
            id = id, title = title, content = content, createdAt = 10L,
            updatedAt = updatedAt, folderId = null, tagIds = emptyList(),
            pinned = false, favorite = false, archived = false,
            deleted = false, deletedAt = null, reminderAt = null,
            wordCount = 1, dailyDate = null, lockJson = null,
            isTemplate = false, dailyDefault = null,
        )

    private fun snapshot(
        notes: List<BxNote> = emptyList(),
        attachments: List<BxAttachmentMeta> = emptyList(),
        tombstones: List<BxTombstone> = emptyList(),
        owner: String? = "uid-1",
        device: String? = "device-a",
    ) = BackupWriter.ExportData(
        manifest = BackupManifest(
            backupFormatVersion = 2, ownerId = owner, libraryId = "lib",
            nomaVersion = "0.4.0", createdAt = "2026-11-02T00:00:00Z",
            noteCount = notes.size, folderCount = 0, tagCount = 0,
            attachmentCount = attachments.size, reminderCount = 0,
            deviceId = device,
        ),
        folders = emptyList(), tags = emptyList(), reminders = emptyList(),
        syncSyncedAtIso = "2026-11-02T00:00:00Z",
        notes = notes, attachments = attachments, tombstones = tombstones,
    )

    private fun merge(
        local: BackupWriter.ExportData,
        remote: BackupWriter.ExportData,
        base: Map<String, String> = emptyMap(),
        localContent: Map<String, String> = emptyMap(),
        remoteContent: Map<String, String> = emptyMap(),
        now: Long = 10_000L,
    ) = SyncEngine.merge(
        local, remote, base, localContent, remoteContent,
        nowMillis = now, nowIso = "2026-11-02T00:00:10Z",
        newId = { "gen-${ids.incrementAndGet()}" },
    )

    private fun baseOf(vararg snapshots: BackupWriter.ExportData): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        for (snap in snapshots) {
            merged.putAll(SyncEngine.snapshotHashes(snap, emptyMap()))
        }
        return merged
    }

    @Test
    fun remoteOnlyNoteIsPulled() {
        val local = snapshot()
        val remote = snapshot(notes = listOf(note("n1", "Remote", 100L)))
        val outcome = merge(local, remote)
        assertEquals(listOf("n1"), outcome.merged.notes.map { it.id })
        assertTrue(outcome.changedVsLocal > 0)
        assertEquals(0, outcome.changedVsRemote)
    }

    @Test
    fun localOnlyNoteIsKeptAndMarkedForPush() {
        val local = snapshot(notes = listOf(note("n1", "Local", 100L)))
        val remote = snapshot()
        val outcome = merge(local, remote)
        assertEquals(listOf("n1"), outcome.merged.notes.map { it.id })
        assertEquals(0, outcome.changedVsLocal)
        assertTrue(outcome.changedVsRemote > 0)
    }

    @Test
    fun disjointEditsConverge() {
        val baseSnap = snapshot(
            notes = listOf(note("a", "A", 100L), note("b", "B", 100L)),
        )
        val base = baseOf(baseSnap)
        val local = snapshot(
            notes = listOf(note("a", "A edited", 200L), note("b", "B", 100L)),
        )
        val remote = snapshot(
            notes = listOf(note("a", "A", 100L), note("b", "B edited", 300L)),
        )
        val outcome = merge(local, remote, base)
        val byId = outcome.merged.notes.associateBy { it.id }
        assertEquals("A edited", byId["a"]?.title)
        assertEquals("B edited", byId["b"]?.title)
        assertTrue(outcome.conflictTitles.isEmpty())
    }

    @Test
    fun bothSidesEdit_newerKeepsId_loserBecomesConflictCopy() {
        val base = baseOf(snapshot(notes = listOf(note("n1", "Original", 100L))))
        val local = snapshot(notes = listOf(note("n1", "Local", 300L)))
        val remote = snapshot(notes = listOf(note("n1", "Remote", 200L)))
        val outcome = merge(local, remote, base)
        val kept = outcome.merged.notes.first { it.id == "n1" }
        assertEquals("Local", kept.title)
        val copy = outcome.merged.notes.firstOrNull { it.title == "Remote (conflict copy)" }
        assertTrue(copy != null && copy.id != "n1")
        assertEquals(listOf("Remote (conflict copy)"), outcome.conflictTitles)
    }

    @Test
    fun tombstoneKillsOlderVersion_newerVersionRevives() {
        val tomb = BxTombstone("note", "n1", deletedAt = 500L, deviceId = "device-b")
        val local = snapshot(notes = listOf(note("n1", "Old", 100L)))
        val remote = snapshot(tombstones = listOf(tomb))
        val outcome = merge(local, remote)
        assertTrue(outcome.merged.notes.isEmpty())
        assertTrue(outcome.mergedTombstones.any { it.id == "n1" })

        val revived = merge(
            snapshot(notes = listOf(note("n1", "Back", 900L))),
            snapshot(tombstones = listOf(tomb)),
        )
        assertEquals(listOf("n1"), revived.merged.notes.map { it.id })
        assertTrue(revived.mergedTombstones.none { it.id == "n1" })
    }

    @Test
    fun absenceWithoutTombstoneIsNotDeletion() {
        val base = baseOf(snapshot(notes = listOf(note("n1", "Kept", 100L))))
        val local = snapshot(notes = listOf(note("n1", "Kept", 100L)))
        val remote = snapshot()
        val outcome = merge(local, remote, base)
        assertEquals(listOf("n1"), outcome.merged.notes.map { it.id })
    }

    @Test
    fun ownEchoIsACompleteNoOp() {
        val snap = snapshot(notes = listOf(note("n1", "Same", 100L)))
        val base = baseOf(snap)
        val outcome = merge(snap, snap, base)
        assertEquals(0, outcome.changedVsLocal)
        assertEquals(0, outcome.changedVsRemote)
        assertTrue(outcome.conflictTitles.isEmpty())
    }

    @Test
    fun oldTombstonesArePurgedAtNinetyDays() {
        val ancient = BxTombstone("note", "gone", deletedAt = 1L, deviceId = "d")
        val outcome = merge(
            snapshot(tombstones = listOf(ancient)),
            snapshot(),
            now = SyncStore.RETENTION_MILLIS + 10_000L,
        )
        assertTrue(outcome.mergedTombstones.none { it.id == "gone" })
    }

    @Test
    fun attachmentByteConflictClonesTheLoser() {
        val att = BxAttachmentMeta(
            id = "a1", noteId = "n1", name = "photo.png", mimeType = "image/png",
            size = 3L, createdAt = 10L, updatedAt = 100L, encrypted = false,
        )
        val baseSnap = snapshot(
            notes = listOf(note("n1", "N", 100L)),
            attachments = listOf(att),
        )
        val base = HashMap(SyncEngine.snapshotHashes(baseSnap, mapOf("a1" to "basehash")))
        val local = snapshot(notes = listOf(note("n1", "N", 100L)), attachments = listOf(att))
        val remote = snapshot(notes = listOf(note("n1", "N", 100L)), attachments = listOf(att))
        val outcome = merge(
            local, remote, base,
            localContent = mapOf("a1" to "localhash"),
            remoteContent = mapOf("a1" to "remotehash"),
        )
        val mergedAtts = outcome.merged.attachments
        assertEquals(2, mergedAtts.size)
        assertTrue(mergedAtts.any { it.id == "a1" })
        val clone = mergedAtts.first { it.id != "a1" }
        assertEquals("n1", clone.noteId)
        assertEquals("remotehash", outcome.mergedAttachmentContent[clone.id])
        assertTrue(outcome.conflictAttachmentSources.containsKey(clone.id))
    }

    @Test
    fun mergedManifestCountsMatchMergedContent() {
        val local = snapshot(notes = listOf(note("a", "A", 100L)))
        val remote = snapshot(notes = listOf(note("b", "B", 100L)))
        val outcome = merge(local, remote)
        assertEquals(2, outcome.merged.manifest.noteCount)
        assertEquals("uid-1", outcome.merged.manifest.ownerId)
    }
}
