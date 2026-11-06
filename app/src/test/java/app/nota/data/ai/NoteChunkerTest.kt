package app.nota.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Note chunking (Phase 4.2): paragraph-aware packing around the
 * 500-char target, word-boundary hard splits with overlap for
 * over-long blocks, and the whole-note cap. Pure JVM — the
 * chunker is deterministic text math.
 */
class NoteChunkerTest {

    @Test
    fun emptyNoteYieldsNoChunks() {
        assertTrue(NoteChunker.chunk("", "").isEmpty())
        assertTrue(NoteChunker.chunk("  ", " \n \n ").isEmpty())
    }

    @Test
    fun shortNoteIsOneChunkWithTheTitleFirst() {
        val chunks = NoteChunker.chunk("Shopping", "milk and eggs")
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].startsWith("Shopping"))
        assertTrue(chunks[0].contains("milk and eggs"))
    }

    @Test
    fun paragraphsPackAroundTheTarget() {
        val paragraphs = (1..6).map { i -> "p$i " + "x".repeat(176) } // 180 chars each
        val body = paragraphs.joinToString("\n")
        val chunks = NoteChunker.chunk("T", body)
        // Title + 2 paragraphs (363) / 2 (361) / 2 (361): the packer
        // never crosses the 500 target by adding another block.
        assertEquals(3, chunks.size)
        for (chunk in chunks) {
            assertTrue("chunk of ${chunk.length}", chunk.length <= NoteChunker.TARGET_CHARS)
        }
        // Nothing lost, nothing reordered.
        val rejoined = chunks.joinToString("\n").split("\n")
        assertEquals(listOf("T") + paragraphs, rejoined)
    }

    @Test
    fun longBlockSplitsAtWordBoundariesWithOverlap() {
        val block = (0 until 400).joinToString(" ") { "w$it" } // ~2,090 chars, unique words
        val chunks = NoteChunker.chunk("", block)
        assertTrue("expected several pieces, got ${chunks.size}", chunks.size >= 3)
        for (chunk in chunks) {
            assertTrue("piece of ${chunk.length}", chunk.length <= NoteChunker.MAX_CHUNK_CHARS)
        }
        // Consecutive pieces overlap: the next piece's opening
        // words already appeared at the previous piece's tail.
        for (i in 0 until chunks.size - 1) {
            val opening = chunks[i + 1].take(12)
            assertTrue(
                "piece ${i + 1} should overlap piece $i",
                chunks[i].takeLast(NoteChunker.OVERLAP_CHARS + 12).contains(opening),
            )
        }
        // Every word survives in exactly the pieces that cover it:
        // the union of pieces contains the whole block's vocabulary.
        val union = chunks.joinToString(" ")
        for (w in listOf("w0", "w137", "w260", "w399")) {
            assertTrue(union.contains(w))
        }
    }

    @Test
    fun splitNeverDropsToZeroOverlap() {
        // Jules PR #23 P3: with a space exactly at cut - 1, the
        // word-start adjustment used to move the next piece's
        // start to the cut itself — consecutive pieces shared
        // ZERO characters, so a phrase straddling the cut landed
        // whole in neither piece.
        val block = "a".repeat(599) + " " + "b".repeat(300)
        val chunks = NoteChunker.chunk("", block)
        assertEquals(2, chunks.size)
        // The second piece re-covers a real tail of the first…
        assertTrue(chunks[1].startsWith(chunks[0].takeLast(NoteChunker.OVERLAP_CHARS)))
        // …and nothing is lost: the space and the whole b-run
        // survive in the second piece.
        assertTrue(chunks[1].contains(" " + "b".repeat(300)))
    }

    @Test
    fun wholeNoteTextIsCappedKeepingTheHead() {
        val text = NoteChunker.embeddedText("HeadTitle", "y".repeat(20_000))
        assertEquals(NoteChunker.MAX_TEXT_CHARS, text.length)
        assertTrue(text.startsWith("HeadTitle"))
    }
}
