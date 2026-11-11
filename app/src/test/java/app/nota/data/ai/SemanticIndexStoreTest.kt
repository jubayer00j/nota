package app.nota.data.ai

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Vector-store persistence (Phase 4.2): binary round-trip,
 * corrupt/truncated files reading as "no index" (the index is
 * derived data — a bad file must cost a rebuild, never a crash),
 * and per-account file isolation. Pure JVM over a temp dir.
 */
class SemanticIndexStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = SemanticIndexStore(File(folder.root, "idx"))

    private fun sampleIndex(): StoredSemanticIndex = StoredSemanticIndex(
        signature = "gemini|native|gemini-embedding-001",
        dims = 3,
        notes = linkedMapOf(
            "note-1" to StoredNoteVectors(
                "fp-1",
                listOf(
                    StoredChunk("cfp-1a", floatArrayOf(0.1f, 0.2f, 0.3f)),
                    StoredChunk("cfp-1b", floatArrayOf(-1f, 0f, 1f)),
                ),
            ),
            "note-2" to StoredNoteVectors(
                "fp-2",
                listOf(StoredChunk("cfp-2a", floatArrayOf(3.25f, -0.5f, 0.125f))),
            ),
        ),
    )

    @Test
    fun roundTripPreservesEverything() {
        val store = store()
        store.save("guest", sampleIndex())
        val loaded = store.load("guest")!!
        assertEquals("gemini|native|gemini-embedding-001", loaded.signature)
        assertEquals(3, loaded.dims)
        assertEquals(setOf("note-1", "note-2"), loaded.notes.keys)
        assertEquals("fp-1", loaded.notes["note-1"]!!.fingerprint)
        // Per-chunk fingerprints round-trip too (format v2, G10).
        assertEquals("cfp-1a", loaded.notes["note-1"]!!.chunks[0].fingerprint)
        val chunk = loaded.notes["note-1"]!!.chunks[1]
        assertEquals("cfp-1b", chunk.fingerprint)
        assertEquals(-1f, chunk.vector[0])
        assertEquals(0f, chunk.vector[1])
        assertEquals(1f, chunk.vector[2])
        assertEquals(3.25f, loaded.notes["note-2"]!!.chunks[0].vector[0])
    }

    @Test
    fun version1FilesReadAsNoIndex() {
        // Format v1 stored bare vectors — no per-chunk
        // fingerprints exist to validate chunks against, so a
        // v1 file reads as "no index" under the store's standing
        // contract (an unreadable file costs a rebuild, never a
        // crash) and the first reconcile under v2 rebuilds it.
        // The bytes below are the v1 layout, written by hand.
        val store = store()
        val file = store.fileFor("guest")
        file.parentFile.mkdirs()
        DataOutputStream(FileOutputStream(file)).use { output ->
            output.writeInt(0x4E494458) // MAGIC "NIDX"
            output.writeInt(1) // VERSION 1
            output.writeUTF("gemini|native|gemini-embedding-001")
            output.writeInt(3) // dims
            output.writeInt(1) // note count
            output.writeUTF("note-1")
            output.writeUTF("fp-1")
            output.writeInt(1) // chunk count
            output.writeInt(3) // vector size
            output.writeFloat(0.1f)
            output.writeFloat(0.2f)
            output.writeFloat(0.3f)
        }
        assertNull(store.load("guest"))
    }

    @Test
    fun missingCorruptAndTruncatedFilesReadAsNoIndex() {
        val store = store()
        assertNull(store.load("guest"))
        val file = store.fileFor("guest")
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertNull(store.load("guest"))
        // A valid file cut short also reads as no index.
        store.save("guest", sampleIndex())
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(bytes.size / 2))
        assertNull(store.load("guest"))
    }

    @Test
    fun accountsAreIsolatedAndDeleteIsScoped() {
        val store = store()
        store.save("guest", sampleIndex())
        store.save("uid-42", sampleIndex().copy(signature = "openai|https://api.openai.com/v1|text-embedding-3-large"))
        assertTrue(store.fileFor("guest").isFile)
        assertTrue(store.fileFor("uid-42").isFile)
        assertFalse(store.fileFor("guest") == store.fileFor("uid-42"))
        store.delete("guest")
        assertNull(store.load("guest"))
        assertEquals(
            "openai|https://api.openai.com/v1|text-embedding-3-large",
            store.load("uid-42")!!.signature,
        )
    }

    @Test
    fun failedCopyFallbackCleansUpTempAndLeavesNoPartialTarget() {
        // Jules PR #23 P3: the renameTo fallback copy was
        // unwrapped — a mid-copy failure (disk full) propagated
        // AND left a truncated target plus a stale .tmp behind.
        // A NON-EMPTY directory squatting on the target path
        // reproduces that failure class without mocking heroics:
        // renameTo cannot replace it, and the fallback copy's
        // overwrite cannot delete it, so the copy throws. (An
        // empty directory would not do: copyTo simply deletes
        // and replaces it.)
        val store = store()
        val target = store.fileFor("guest")
        target.parentFile.mkdirs()
        target.mkdir()
        File(target, "squatter").writeBytes(byteArrayOf(1))
        try {
            store.save("guest", sampleIndex())
            fail("expected the copy failure to propagate")
        } catch (e: Exception) {
            // Expected: the failure still reaches the caller.
        }
        // No stale temp file is left behind…
        assertFalse(File(folder.root, "idx/" + target.name + ".tmp").exists())
        // …and no partial target poses as an index.
        assertNull(store.load("guest"))
    }

    @Test
    fun saveOverwritesAtomically() {
        val store = store()
        store.save("guest", sampleIndex())
        store.save("guest", sampleIndex().copy(notes = emptyMap()))
        val loaded = store.load("guest")!!
        assertTrue(loaded.notes.isEmpty())
        // No temp file is left behind.
        assertFalse(File(folder.root, "idx/" + store.fileFor("guest").name + ".tmp").exists())
    }
}
