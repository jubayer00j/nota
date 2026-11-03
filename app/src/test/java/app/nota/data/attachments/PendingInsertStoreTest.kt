package app.nota.data.attachments

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3.6 (G2): the staged picker-insert record must survive
 * process death — that is the whole point of it. Survival is proven
 * the only way it can happen on device: a brand-new store instance
 * over the same directory (a new process reading the same filesDir)
 * sees exactly what the previous instance staged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingInsertStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun record(
        id: String = "rec-1",
        accountId: String = "guest",
        noteId: String = "note-1",
    ) = PendingInsertRecord(
        id = id,
        accountId = accountId,
        noteId = noteId,
        kind = PendingInsertKind.FILE,
        mode = PendingInsertMode.INSERT_AT_CARET,
        segmentIndex = 3,
        segmentAnchorHash = PendingInsertRecord.anchorHash("Bravo middle paragraph"),
        segmentAnchorLength = "Bravo middle paragraph".length,
        caretOffset = 7,
        caretEndOffset = 12,
        stagedAtMillis = 123_456L,
    )

    @Test
    fun stagedRecord_survivesNewInstanceOverSameDirectory() {
        val dir = tempFolder.newFolder("files")
        val first = PendingInsertStore(dir)
        first.stage(record())

        // A new instance = a new process after death in the picker.
        val second = PendingInsertStore(dir)
        val restored = second.peek()
        assertEquals(record(), restored)
        assertEquals(PendingInsertKind.FILE, restored!!.kind)
        assertEquals(PendingInsertMode.INSERT_AT_CARET, restored.mode)
        assertEquals(3, restored.segmentIndex)
        assertEquals(
            PendingInsertRecord.anchorHash("Bravo middle paragraph"),
            restored.segmentAnchorHash,
        )
        assertEquals(22, restored.segmentAnchorLength)
        assertEquals(7, restored.caretOffset)
        // The selection END survives too: a picker insert over a
        // selected range must keep Phase 3.5's replace-selection
        // behaviour after the round trip (Codex, PR #19).
        assertEquals(12, restored.caretEndOffset)
        assertEquals(12, restored.selectionEnd)
    }

    @Test
    fun stateFlow_publishesStagedRecord() {
        val store = PendingInsertStore(tempFolder.newFolder("files"))
        assertNull(store.state.value)
        store.stage(record())
        assertEquals(record(), store.state.value)
    }

    @Test
    fun clear_removesRecordForLaterInstances() {
        val dir = tempFolder.newFolder("files")
        val store = PendingInsertStore(dir)
        store.stage(record())
        store.clear()
        assertNull(store.peek())
        assertNull(PendingInsertStore(dir).peek())
    }

    @Test
    fun clearIf_onlyRetiresTheNamedRecord() {
        val store = PendingInsertStore(tempFolder.newFolder("files"))
        store.stage(record(id = "rec-old"))
        store.stage(record(id = "rec-new"))
        // A stale consumer retiring its own record must not kill the
        // newer staged insert.
        store.clearIf("rec-old")
        assertEquals("rec-new", store.peek()?.id)
        store.clearIf("rec-new")
        assertNull(store.peek())
    }

    @Test
    fun corruptFile_readsAsNoRecord_notACrash() {
        val dir = tempFolder.newFolder("files")
        File(dir, PendingInsertStore.FILE_NAME).writeText("{not json")
        assertNull(PendingInsertStore(dir).peek())
    }

    @Test
    fun recordJson_roundTrips() {
        val original = record()
        assertEquals(original, PendingInsertRecord.parse(original.toJson()))
    }

    @Test
    fun stagedRecord_containsNoAnchorPlaintext() {
        // The anchor is a digest by design (Codex P1, PR #19): this
        // record is a plaintext file in filesDir, and for a per-note
        // locked note the anchor text would be decrypted content —
        // also eligible for platform backup. Neither the JSON form
        // nor the on-disk file may contain the segment's words.
        assertFalse(record().toJson().contains("Bravo"))
        val dir = tempFolder.newFolder("files")
        PendingInsertStore(dir).stage(record())
        val onDisk = File(dir, PendingInsertStore.FILE_NAME).readText()
        assertFalse(onDisk.contains("Bravo"))
        assertEquals(record(), PendingInsertRecord.parse(onDisk))
    }

    @Test
    fun reversedSelection_endSurvivesUncollapsed() {
        // A right-to-left selection stages start 12, end 7 (Codex
        // P2, PR #19). Collapsing the end up to the start would
        // make the restore's range empty, so the picker insert
        // would land WITHOUT deleting the selected text.
        assertEquals(7, record().copy(caretOffset = 12, caretEndOffset = 7).selectionEnd)
        assertEquals(12, record().copy(caretOffset = 7, caretEndOffset = 12).selectionEnd)
        // No distinct end staged (-1): the caret itself.
        assertEquals(9, record().copy(caretOffset = 9, caretEndOffset = -1).selectionEnd)
    }

    @Test
    fun legacyJson_plaintextAnchorConvertsToDigest() {
        // A record staged by the build before the anchor was hashed
        // (and before caretEndOffset existed): the plaintext field
        // converts on read, the restore keeps working, and the text
        // is never retained or re-serialised.
        val legacy = """
            {"id":"rec-legacy","accountId":"guest","noteId":"note-1",
             "kind":"FILE","mode":"INSERT_AT_CARET","segmentIndex":3,
             "segmentAnchor":"Bravo middle paragraph","caretOffset":7,
             "stagedAtMillis":99}
        """.trimIndent()
        val parsed = PendingInsertRecord.parse(legacy)!!
        assertEquals(
            PendingInsertRecord.anchorHash("Bravo middle paragraph"),
            parsed.segmentAnchorHash,
        )
        assertEquals(22, parsed.segmentAnchorLength)
        assertEquals(7, parsed.caretEndOffset)
        assertEquals(7, parsed.selectionEnd)
        assertFalse(parsed.toJson().contains("Bravo"))
    }
}
