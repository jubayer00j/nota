package app.nota.data.ai

import java.io.File
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
                listOf(floatArrayOf(0.1f, 0.2f, 0.3f), floatArrayOf(-1f, 0f, 1f)),
            ),
            "note-2" to StoredNoteVectors(
                "fp-2",
                listOf(floatArrayOf(3.25f, -0.5f, 0.125f)),
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
        val chunk = loaded.notes["note-1"]!!.chunks[1]
        assertEquals(-1f, chunk[0])
        assertEquals(0f, chunk[1])
        assertEquals(1f, chunk[2])
        assertEquals(3.25f, loaded.notes["note-2"]!!.chunks[0][0])
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
        // A directory squatting on the target path makes renameTo
        // fail and the fallback copy throw, reproducing that
        // failure class without any mocking heroics.
        val store = store()
        val target = store.fileFor("guest")
        target.parentFile.mkdirs()
        target.mkdir()
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
