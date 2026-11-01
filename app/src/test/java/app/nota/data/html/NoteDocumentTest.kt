package app.nota.data.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HTML guard layer (SPIKE-REPORT condition 1): fragile block shapes
 * must survive parse -> serialize -> parse unchanged, and canonical
 * shapes must serialise byte-for-byte. These fixtures mirror the HTML
 * Noma 1.x (Tiptap) actually stores.
 */
class NoteDocumentTest {

    private val fullNote = "<h1>Physics notes</h1>" +
        "<p>Marks in <strong>bold</strong> and a <a href=\"https://example.com\">link</a> " +
        "plus <mark>highlight</mark> and [[Chemistry]]</p>" +
        "<ul data-type=\"taskList\">" +
        "<li data-type=\"taskItem\" data-checked=\"true\"><div><p>Revise optics</p></div></li>" +
        "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Revise thermo</p></div></li></ul>" +
        "<table><tbody><tr><th>Subject</th><th>Score</th></tr>" +
        "<tr><td>Physics</td><td>133</td></tr>" +
        "<tr><td>Chemistry</td><td>115</td></tr></tbody></table>" +
        "<blockquote><p>A quoted line.</p></blockquote>" +
        "<pre><code>val x = 1 &lt; 2</code></pre>" +
        "<hr>" +
        "<p><img src=\"nota-attachment://abc\" alt=\"diagram\"></p>" +
        "<p>Closing paragraph.</p>"

    @Test
    fun parsesIntoExpectedSegments() {
        val segments = NoteDocument.parse(fullNote)
        assertEquals(
            listOf(
                "TextSegment", "TaskListSegment", "TableSegment", "QuoteSegment",
                "CodeSegment", "DividerSegment", "ImageSegment", "TextSegment",
            ),
            segments.map { it::class.simpleName },
        )
        val tasks = segments[1] as TaskListSegment
        assertEquals(2, tasks.items.size)
        assertTrue(tasks.items[0].checked)
        assertTrue(!tasks.items[1].checked)
        val table = segments[2] as TableSegment
        assertTrue(table.headerRow)
        assertEquals(listOf("Subject", "Score"), table.rows[0])
        assertEquals(listOf("Physics", "133"), table.rows[1])
        assertEquals("val x = 1 < 2", (segments[4] as CodeSegment).text)
        assertEquals("abc", (segments[6] as ImageSegment).src.removePrefix("nota-attachment://"))
        assertEquals("diagram", (segments[6] as ImageSegment).alt)
    }

    @Test
    fun roundTripIsStableAndCanonical() {
        val once = NoteDocument.serialize(NoteDocument.parse(fullNote))
        val twice = NoteDocument.serialize(NoteDocument.parse(once))
        assertEquals(once, twice)
        // Canonical shapes survive byte-for-byte.
        assertTrue(once.contains("<table><tbody><tr><th>Subject</th><th>Score</th></tr>"))
        assertTrue(once.contains("data-checked=\"true\""))
        assertTrue(once.contains("<pre><code>val x = 1 &lt; 2</code></pre>"))
        assertTrue(once.contains("<hr>"))
        assertTrue(once.contains("alt=\"diagram\""))
    }

    @Test
    fun mergedTableCellsKeepSpansThroughRoundTrip() {
        // Merged cells (Codex F8): spans must survive parse -> serialize
        // or any edit silently un-merges the table on re-save.
        val html = "<table><tbody>" +
            "<tr><th colspan=\"2\">Head</th><th rowspan=\"2\">Side</th></tr>" +
            "<tr><td>a</td><td>b</td></tr>" +
            "<tr><td colspan=\"3\">wide</td></tr></tbody></table>"
        val segments = NoteDocument.parse(html)
        val table = segments.single() as TableSegment
        assertEquals(TableCellSpan(colSpan = 2), table.cellSpans[0][0])
        assertEquals(TableCellSpan(rowSpan = 2), table.cellSpans[0][2])
        assertEquals(TableCellSpan(), table.cellSpans[1][0])
        assertEquals(TableCellSpan(colSpan = 3), table.cellSpans[2][0])
        val once = NoteDocument.serialize(segments)
        assertEquals(html, once)
        assertEquals(once, NoteDocument.serialize(NoteDocument.parse(once)))
        assertEquals(table, NoteDocument.parse(once).single())
    }

    @Test
    fun plainTextProjectionCoversBlocks() {
        val text = NoteDocument.plainText(NoteDocument.parse(fullNote))
        assertTrue(text.contains("Physics notes"))
        assertTrue(text.contains("Revise optics"))
        assertTrue(text.contains("Physics 133"))
        assertTrue(text.contains("A quoted line."))
        assertTrue(text.contains("val x = 1 < 2"))
        assertTrue(text.contains("diagram"))
        assertTrue(text.contains("[[Chemistry]]"))
    }

    @Test
    fun headingsAndBacklinksExtract() {
        val segments = NoteDocument.parse(fullNote)
        val headings = NoteDocument.headings(segments)
        assertEquals(1, headings.size)
        assertEquals(1, headings[0].level)
        assertEquals("Physics notes", headings[0].title)
        assertEquals(
            listOf("Chemistry"),
            NoteDocument.backlinkTitles(NoteDocument.plainText(segments)),
        )
    }

    @Test
    fun tiptapTaskListWithCheckboxScaffoldingParses() {
        // The fuller shape Tiptap emits: label + input inside the <li>.
        val html = "<ul data-type=\"taskList\">" +
            "<li data-checked=\"true\" data-type=\"taskItem\">" +
            "<label><input type=\"checkbox\" checked=\"checked\"><span></span></label>" +
            "<div><p>Done item</p></div></li>" +
            "<li data-checked=\"false\" data-type=\"taskItem\">" +
            "<label><input type=\"checkbox\"><span></span></label>" +
            "<div><p>Open item</p></div></li></ul>"
        val segments = NoteDocument.parse(html)
        val tasks = segments.single() as TaskListSegment
        assertEquals(2, tasks.items.size)
        assertTrue(tasks.items[0].checked)
        assertTrue(!tasks.items[1].checked)
        assertEquals("<p>Done item</p>", tasks.items[0].contentHtml)
        val reserialised = NoteDocument.serialize(segments)
        assertEquals(tasks, (NoteDocument.parse(reserialised).single() as TaskListSegment))
    }

    @Test
    fun unknownBlocksArePreservedVerbatim() {
        val weird = "<div class=\"custom-widget\" data-x=\"1\"><span>hi</span></div>"
        val segments = NoteDocument.parse("<p>a</p>$weird<p>b</p>")
        assertEquals(weird, (segments[1] as OpaqueSegment).rawHtml)
        assertEquals("<p>a</p>$weird<p>b</p>", NoteDocument.serialize(segments))
    }

    @Test
    fun chartBlocksArePreservedVerbatim() {
        val chart = "<div data-nota-block=\"chart\" data-payload=\"{}\"><canvas></canvas></div>"
        val segments = NoteDocument.parse(chart)
        val parsed = segments.single() as ChartSegment
        assertEquals("{}", parsed.payload)
        assertEquals(chart, NoteDocument.serialize(segments))
    }

    @Test
    fun imageInsideParagraphSplitsOut() {
        val html = "<p>before <img src=\"http://x/y.png\" alt=\"pic\"> after</p>"
        val segments = NoteDocument.parse(html)
        assertEquals(
            listOf("TextSegment", "ImageSegment", "TextSegment"),
            segments.map { it::class.simpleName },
        )
        assertEquals("pic", (segments[1] as ImageSegment).alt)
    }

    @Test
    fun everydayShapesStayTextSegments() {
        // The library-lossless core must NOT be segmented.
        val html = "<h2>Head</h2><p>one</p><ul><li><p>a</p></li><li><p>b</p></li></ul>" +
            "<ol><li><p>first</p></li></ol><p>tail</p>"
        val segments = NoteDocument.parse(html)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is TextSegment)
        assertEquals(html, NoteDocument.serialize(segments))
    }

    @Test
    fun blankDocumentIsOneEmptyTextSegment() {
        assertEquals(listOf(TextSegment("")), NoteDocument.parse(""))
        assertEquals("", NoteDocument.serialize(NoteDocument.parse("")))
    }
}
