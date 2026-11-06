package app.nota.data.capture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 5.2: the durable capture queue. Survival is proven the
 * PendingInsertStore way — a brand-new store instance over the same
 * directory (a new process reading the same filesDir) sees exactly
 * what the previous instance staged, account stamps included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingCaptureStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun root(): File = File(tempFolder.root, "pending_captures")

    private fun store() = PendingCaptureStore(root())

    private fun shareRecord(
        id: String,
        accountId: String = "guest",
        title: String = "Shared",
    ) = CaptureRecord(
        id = id,
        accountId = accountId,
        request = CaptureRequest.Share(
            id = id,
            source = CaptureSource.ANDROID_SHARE,
            title = title,
            contentHtml = "<p>body</p>",
            images = listOf(
                CaptureImageRef(name = "photo.jpg", mimeType = "image/jpeg", fileName = "img-0.jpg"),
            ),
        ),
        stagedAtMillis = 42L,
    )

    private fun doorRecord(id: String, accountId: String = "guest") = CaptureRecord(
        id = id,
        accountId = accountId,
        request = CaptureRequest.NewNote(id, CaptureSource.TILE),
        stagedAtMillis = 7L,
    )

    @Test
    fun stagedRecords_surviveANewStoreInstance_withAccountStamps() {
        val first = store()
        first.stage(shareRecord("cap-1", accountId = "uid-a"))
        first.stage(doorRecord("cap-2", accountId = "uid-a"))

        val second = store()
        assertEquals(listOf("cap-1", "cap-2"), second.records.value.map { it.id })
        assertEquals("uid-a", second.records.value[0].accountId)
        val share = second.records.value[0].request as CaptureRequest.Share
        assertEquals("Shared", share.title)
        assertEquals("<p>body</p>", share.contentHtml)
        assertEquals("img-0.jpg", share.images.single().fileName)
        assertTrue(second.records.value[1].request is CaptureRequest.NewNote)
    }

    @Test
    fun head_isTheOldestRecord() {
        val store = store()
        store.stage(doorRecord("cap-1"))
        store.stage(doorRecord("cap-2"))
        assertEquals("cap-1", store.head()?.id)
    }

    @Test
    fun take_removesFromQueue_butLeavesStagedFiles() {
        val store = store()
        val record = shareRecord("cap-1")
        val dir = store.stagingDir(record.id)
        File(dir, "img-0.jpg").writeBytes(byteArrayOf(1, 2, 3))
        store.stage(record)

        val taken = store.take("cap-1")
        assertEquals(record.id, taken?.id)
        assertNull(store.head())
        // Delivery reads the images AFTER taking — files must stay.
        assertTrue(File(dir, "img-0.jpg").exists())
        // And the deliverer's cleanup removes them.
        store.deleteStaging(record)
        assertFalse(dir.exists())
    }

    @Test
    fun remove_deletesStagedFiles_too() {
        val store = store()
        val record = shareRecord("cap-1")
        val dir = store.stagingDir(record.id)
        File(dir, "img-0.jpg").writeBytes(byteArrayOf(9))
        store.stage(record)

        store.remove("cap-1")
        assertNull(store.head())
        assertFalse(dir.exists())
    }

    @Test
    fun queueCap_dropsTheOldest_andUnstagesIt() {
        val store = store()
        val firstDir = store.stagingDir("cap-0")
        File(firstDir, "img-0.jpg").writeBytes(byteArrayOf(1))
        for (i in 0 until PendingCaptureStore.MAX_PENDING + 2) {
            store.stage(doorRecord("cap-$i"))
        }
        val ids = store.records.value.map { it.id }
        assertEquals(PendingCaptureStore.MAX_PENDING, ids.size)
        assertFalse(ids.contains("cap-0"))
        assertFalse(ids.contains("cap-1"))
        assertEquals("cap-2", ids.first())
        assertFalse(firstDir.exists())
    }

    @Test
    fun orphanStagingDirs_areSweptOnInit() {
        val store = store()
        store.stage(doorRecord("cap-live"))
        val orphan = File(root(), "cap-ghost").apply { mkdirs() }
        File(orphan, "img-0.jpg").writeBytes(byteArrayOf(5))

        store() // a new instance sweeps
        assertFalse(orphan.exists())
    }

    @Test
    fun emptyStore_hasNoHead() {
        assertNull(store().head())
    }
}
