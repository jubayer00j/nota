package app.nota.data.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 (Editor Refinement #1 fix): block deletion at the document
 * layer. The editor's one removal op drops a segment from the list
 * (the screen's removeBlock) and re-applies the trailing-caret guard;
 * these tests pin the document side of that contract for the two
 * block types that gained a remove affordance in this fix — code and
 * quote:
 *
 *  - removing the segment leaves every other segment byte-stable;
 *  - an emptied segment list is restored to a single empty text
 *    segment by [NoteDocument.ensureTrailingTextSegment] (the
 *    model-level twin of the screen's ensureTrailing), so a document
 *    is never left with zero segments;
 *  - an EMPTY code/quote block round-trips through parse/serialise —
 *    which is exactly why it could never be deleted by emptying it
 *    and needed the explicit affordance (Jubayer's device-test bug:
 *    "the code block can't be cancelled or removed once it is
 *    imported").
 */
class NoteDocumentBlockDeletionTest {

    @Test
    fun codeBlockRemoval_leavesNeighboursByteStable() {
        val html = "<p>Before</p><pre><code>val x = 1</code></pre><p>After</p>"
        val segments = NoteDocument.parse(html)
        assertEquals(
            listOf("TextSegment", "CodeSegment", "TextSegment"),
            segments.map { it::class.simpleName },
        )
        val removed = segments.filterNot { it is CodeSegment }
        assertEquals("<p>Before</p><p>After</p>", NoteDocument.serialize(removed))
    }

    @Test
    fun quoteBlockRemoval_leavesNeighboursByteStable() {
        val html = "<p>Intro</p><blockquote><p>Quoted words</p></blockquote><p>Outro</p>"
        val segments = NoteDocument.parse(html)
        assertEquals(
            listOf("TextSegment", "QuoteSegment", "TextSegment"),
            segments.map { it::class.simpleName },
        )
        val removed = segments.filterNot { it is QuoteSegment }
        assertEquals("<p>Intro</p><p>Outro</p>", NoteDocument.serialize(removed))
    }

    @Test
    fun emptyCodeBlock_roundTrips_soEmptyingItNeverDeletesIt() {
        // The exact shape the insert menu creates: CodeSegment("").
        val html = "<pre><code></code></pre>"
        val segments = NoteDocument.parse(html)
        assertEquals(listOf(CodeSegment("")), segments)
        assertEquals(html, NoteDocument.serialize(segments))
    }

    @Test
    fun emptyQuoteBlock_roundTrips_soEmptyingItNeverDeletesIt() {
        // The exact shape the insert menu creates: QuoteSegment("<p></p>").
        val html = "<blockquote><p></p></blockquote>"
        val segments = NoteDocument.parse(html)
        assertEquals(1, segments.size)
        assertTrue(segments.single() is QuoteSegment)
        assertEquals(html, NoteDocument.serialize(segments))
    }

    @Test
    fun removingTheOnlyBlock_restoresSingleEmptyTextSegment() {
        val segments = NoteDocument.parse("<pre><code>val x = 1</code></pre>")
        val removed = segments.filterNot { it is CodeSegment }
        assertTrue(removed.isEmpty())
        // The guard the screen's removeBlock relies on (via its own
        // ensureTrailing): never zero segments.
        val restored = NoteDocument.ensureTrailingTextSegment(removed)
        assertEquals(listOf(TextSegment("")), restored)
        assertEquals("", NoteDocument.serialize(restored))
        assertFalse(NoteDocument.serialize(restored).contains("<pre"))
    }
}
