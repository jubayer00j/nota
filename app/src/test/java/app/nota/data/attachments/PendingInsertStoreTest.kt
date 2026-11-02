package app.nota.data.attachments

import java.io.File
import org.junit.Assert.assertEquals
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
        segmentAnchor = "Bravo middle paragraph",
        caretOffset = 7,
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
        assertEquals("Bravo middle paragraph", restored.segmentAnchor)
        assertEquals(7, restored.caretOffset)
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
}
