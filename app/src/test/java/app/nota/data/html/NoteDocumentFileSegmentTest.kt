package app.nota.data.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3.5 (D4): the in-document file block ([FileSegment]).
 * Parse/serialize must round-trip byte-for-byte in canonical form,
 * the block keeps its position among the surrounding segments (the
 * document order IS the persistence), its name projects into plain
 * text, and the guard rails hold: a file block counts as a block for
 * the trailing-caret rule, and a div without our id attribute is not
 * claimed — it stays an opaque preservation.
 */
class NoteDocumentFileSegmentTest {

    private val fileDiv =
        "<div data-nota-file=\"att-1\" data-name=\"report.pdf\" " +
            "data-mime=\"application/pdf\"></div>"

    @Test
    fun parse_recognisesFileDiv() {
        val segments = NoteDocument.parse("<p>Before</p>$fileDiv<p>After</p>")
        assertEquals(3, segments.size)
        assertEquals(TextSegment("<p>Before</p>"), segments[0])
        assertEquals(
            FileSegment("att-1", "report.pdf", "application/pdf"),
            segments[1],
        )
        assertEquals(TextSegment("<p>After</p>"), segments[2])
    }

    @Test
    fun serialize_roundTripsByteForByte() {
        val html = "<p>Before</p>$fileDiv<p>After</p>"
        val segments = NoteDocument.parse(html)
        assertEquals(html, NoteDocument.serialize(segments))
        // And the guard's stability invariant: parse ∘ serialize = identity.
        assertEquals(segments, NoteDocument.parse(NoteDocument.serialize(segments)))
    }

    @Test
    fun order_isDocumentOrder() {
        val segments = listOf(
            TextSegment("<p>One</p>"),
            FileSegment("att-1", "a.zip", "application/zip"),
            ImageSegment("nota-attachment://att-2", "pic"),
            FileSegment("att-3", "b.pdf", "application/pdf"),
            TextSegment("<p>Two</p>"),
        )
        val reparsed = NoteDocument.parse(NoteDocument.serialize(segments))
        assertEquals(segments, reparsed)
    }

    @Test
    fun escaping_nameWithQuoteAndAmpersand_roundTrips() {
        val segment = FileSegment("att-9", "Tom & \"Jerry\".pdf", "application/pdf")
        val html = NoteDocument.serialize(listOf(segment))
        assertEquals(listOf(segment), NoteDocument.parse(html))
    }

    @Test
    fun plainText_includesFileName() {
        val text = NoteDocument.plainText(
            listOf(
                TextSegment("<p>See attached</p>"),
                FileSegment("att-1", "report.pdf", "application/pdf"),
            ),
        )
        assertTrue(text.contains("report.pdf"))
        assertTrue(text.contains("See attached"))
    }

    @Test
    fun divWithoutFileId_staysOpaque() {
        val html = "<div class=\"banner\"><p>Hello</p></div>"
        val segments = NoteDocument.parse(html)
        assertEquals(listOf(OpaqueSegment(html)), segments)
    }

    @Test
    fun divWithBlankFileId_staysOpaque() {
        val html = "<div data-nota-file=\"\"><p>Hello</p></div>"
        val segments = NoteDocument.parse(html)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is OpaqueSegment)
    }

    @Test
    fun trailingCaret_fileBlockAtEnd_getsTrailingTextSegment() {
        val working = NoteDocument.ensureTrailingTextSegment(
            listOf(TextSegment("<p>Hi</p>"), FileSegment("att-1", "a.zip")),
        )
        assertEquals(3, working.size)
        assertTrue(working.last() is TextSegment)
        // …and the storage form strips that caret segment back off.
        val stored = NoteDocument.stripTrailingEmptyTextSegments(working)
        assertEquals(
            listOf(TextSegment("<p>Hi</p>"), FileSegment("att-1", "a.zip")),
            stored,
        )
    }
}
