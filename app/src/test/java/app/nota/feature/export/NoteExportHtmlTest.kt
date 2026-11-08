package app.nota.feature.export

import app.nota.data.html.ImageSegment
import app.nota.data.html.TableCellSpan
import app.nota.data.html.TableSegment
import app.nota.data.html.TextSegment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-note Export #1 — the standalone-HTML converter (1.x's
 * noteToHtml semantics): a complete document, the stored HTML
 * with images resolved and writing aids stripped, and strict
 * escaping for anything that is plain text (title, code).
 */
class NoteExportHtmlTest {

    private fun html() = NoteExport.noteToHtml(
        EXPORT_FIXTURE_TITLE, exportFixture(), exportResolver,
    )

    @Test
    fun standaloneDocumentShell() {
        val out = html()
        assertTrue(out.startsWith("<!doctype html>"))
        assertTrue(out.contains("<meta charset=\"utf-8\">"))
        assertTrue(out.contains("<main>"))
        assertTrue(out.trimEnd().endsWith("</html>"))
    }

    @Test
    fun titleIsEscapedInTitleAndHeading() {
        val out = html()
        assertTrue(out.contains("<title>Field notes &amp; &lt;draft&gt;</title>"))
        assertTrue(out.contains("<h1>Field notes &amp; &lt;draft&gt;</h1>"))
        assertFalse(out.contains("<draft>"))
    }

    @Test
    fun scriptLikeTitleCannotInject() {
        val out = NoteExport.noteToHtml(
            "<script>alert(1)</script>", emptyList(),
        )
        assertFalse(out.contains("<script>alert"))
        assertTrue(out.contains("&lt;script&gt;alert(1)&lt;/script&gt;"))
    }

    @Test
    fun storedBlocksPassThroughWithEntitiesRoundTripped() {
        val out = html()
        assertTrue(out.contains("<h2>Shopping &amp; plans</h2>"))
        assertTrue(out.contains("<strong>bold</strong>"))
        assertTrue(out.contains("href=\"https://example.com/list\""))
        assertTrue(out.contains("<ul><li>milk</li>"))
        assertTrue(out.contains("<ol start=\"3\">"))
    }

    @Test
    fun noteLinksBecomeLabelsNotChips() {
        val out = html()
        assertTrue(out.contains("<p>See Groceries and the house list.</p>"))
        assertFalse(out.contains("[["))
    }

    @Test
    fun inlineAttachmentImagesResolveOrPlaceholder() {
        val out = html()
        assertTrue(out.contains("<img src=\"$EXPORT_DATA_URL\" alt=\"Inline cat\">"))
        assertTrue(out.contains("<span>[Image]</span>"))
    }

    @Test
    fun taskListCarriesCheckboxState() {
        val out = html()
        assertTrue(out.contains("<ul data-type=\"taskList\">"))
        assertTrue(
            out.contains(
                "<li data-checked=\"true\"><label>" +
                    "<input type=\"checkbox\" checked disabled></label>" +
                    "<div>Book <strong>flights</strong></div></li>",
            ),
        )
        assertTrue(out.contains("<li data-checked=\"false\">"))
    }

    @Test
    fun tableHonoursHeaderRowAndSpans() {
        val out = html()
        assertTrue(
            out.contains(
                "<table><tr><th>Item</th><th>Qty</th></tr>" +
                    "<tr><td>Rice | Basmati</td><td>2</td></tr></table>",
            ),
        )
        val spanned = NoteExport.noteToHtml(
            "T",
            listOf(
                TableSegment(
                    rows = listOf(listOf("Wide"), listOf("a", "b")),
                    headerRow = false,
                    cellSpans = listOf(
                        listOf(TableCellSpan(rowSpan = 1, colSpan = 2)),
                        listOf(TableCellSpan(), TableCellSpan()),
                    ),
                ),
            ),
        )
        assertTrue(spanned.contains("<td colspan=\"2\">Wide</td>"))
    }

    @Test
    fun codeTextIsEscapedNotInterpreted() {
        val out = html()
        assertTrue(
            out.contains(
                "<pre><code>val x = \"a ``` b\" // &lt;not&gt; a tag</code></pre>",
            ),
        )
        assertFalse(out.contains("<not>"))
    }

    @Test
    fun standaloneImagesAndWidths() {
        val out = html()
        assertTrue(out.contains("<img src=\"$EXPORT_DATA_URL\" alt=\"Cat\">"))
        assertTrue(out.contains("<img src=\"https://example.com/pic.png\" alt=\"Web [pic]\">"))
        val sized = NoteExport.noteToHtml(
            "T",
            listOf(ImageSegment(src = "https://example.com/a.png", widthPercent = 50)),
        )
        assertTrue(sized.contains("width=\"50%\""))
    }

    @Test
    fun quoteFileDividerChartAndOpaque() {
        val out = html()
        assertTrue(
            out.contains("<blockquote><p>Measure <em>twice</em>, cut once.</p></blockquote>"),
        )
        assertTrue(out.contains("<p>[File: tickets.pdf]</p>"))
        assertTrue(out.contains("<hr>"))
        assertTrue(
            out.contains("<p class=\"noma-chart-placeholder\">[Bar chart]</p>"),
        )
        // Opaque blocks are preserved verbatim (their contract).
        assertTrue(out.contains("<div class=\"embed\"><p>Embedded words</p></div>"))
    }
}
