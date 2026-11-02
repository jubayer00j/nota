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
        assertEquals(TableCellSpan(rowSpan = 2), table.cellSpans[0][1])
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
    fun nestedTaskListInsideRegularListIsPreservedVerbatim() {
        // A Tiptap task list nested inside a regular bullet item:
        // the OUTER list is normalised to the bare-item dialect,
        // but the nested task list (segmented and modelled
        // separately) must survive byte-for-byte — its items'
        // <div><p> scaffolding is not the editor-hostile shape
        // normalisation exists to remove. (Codex P2, Phase 2.5.)
        val taskList = "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"true\"><div><p>Revise optics</p></div></li>" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>Revise thermo</p></div></li></ul>"
        val html = "<ul><li><p>Shopping</p>$taskList</li><li><p>Dinner</p></li></ul>"
        val reserialised = NoteDocument.serialize(NoteDocument.parse(html))
        assertEquals(
            "<ul><li>Shopping$taskList</li><li>Dinner</li></ul>",
            reserialised,
        )
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
        // The library-lossless core must NOT be segmented. Tiptap's
        // <li><p>…</p></li> list dialect is normalised to the bare-item
        // shape on the way in (Phase 2.5 fix F1: the library's setHtml
        // drops a list whose items wrap their content in <p>), so the
        // serialised form differs from the 1.x input exactly there —
        // everything else is byte-for-byte.
        val html = "<h2>Head</h2><p>one</p><ul><li><p>a</p></li><li><p>b</p></li></ul>" +
            "<ol><li><p>first</p></li></ol><p>tail</p>"
        val segments = NoteDocument.parse(html)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is TextSegment)
        assertEquals(
            "<h2>Head</h2><p>one</p><ul><li>a</li><li>b</li></ul>" +
                "<ol><li>first</li></ol><p>tail</p>",
            NoteDocument.serialize(segments),
        )
    }

    @Test
    fun blankDocumentIsOneEmptyTextSegment() {
        assertEquals(listOf(TextSegment("")), NoteDocument.parse(""))
        assertEquals("", NoteDocument.serialize(NoteDocument.parse("")))
    }

    @Test
    fun bareUnknownBlockElementsAreOpaque() {
        // Codex fix G6: a bare <video> (not wrapped in div/figure) used to
        // fall into the text path and be mangled by the editor.
        val video = "<video src=\"clip.mp4\" controls>" +
            "<source src=\"clip.mp4\" type=\"video/mp4\">fallback</video>"
        val html = "<p>a</p>$video<p>b</p>"
        val segments = NoteDocument.parse(html)
        assertEquals(
            listOf("TextSegment", "OpaqueSegment", "TextSegment"),
            segments.map { it::class.simpleName },
        )
        assertEquals(video, (segments[1] as OpaqueSegment).rawHtml)
        assertEquals(html, NoteDocument.serialize(segments))
        assertEquals(segments, NoteDocument.parse(NoteDocument.serialize(segments)))
    }

    @Test
    fun bareIframeAndEmbedAreOpaque() {
        val iframe = "<iframe src=\"https://example.com/embed\"></iframe>"
        val iframeSegments = NoteDocument.parse(iframe)
        assertEquals(listOf("OpaqueSegment"), iframeSegments.map { it::class.simpleName })
        assertEquals(iframe, NoteDocument.serialize(iframeSegments))

        val embed = "<embed src=\"movie.swf\">"
        val embedSegments = NoteDocument.parse(embed)
        assertEquals(listOf("OpaqueSegment"), embedSegments.map { it::class.simpleName })
        assertEquals(embed, NoteDocument.serialize(embedSegments))
    }

    @Test
    fun knownInlineTagsStillFlowAsText() {
        // G6 must not reclassify anything previously textual.
        val html = "<p>one</p><strong>bold</strong> tail"
        val segments = NoteDocument.parse(html)
        assertEquals(1, segments.size)
        assertTrue(segments[0] is TextSegment)
        assertEquals(html, NoteDocument.serialize(segments))
    }

    // ------------------------------------------------ Phase 1.5 fix 2
    // Trailing caret segment: the editor's working state always ends in
    // exactly one text segment; the stored form never gains it.

    @Test
    fun trailingBlockGetsOneEmptyTextSegment() {
        val fixtures = listOf(
            "<p>a</p><p><img src=\"nota-attachment://abc\" alt=\"d\"></p>",
            "<p>a</p><table><tbody><tr><td>1</td></tr></tbody></table>",
            "<p>a</p><ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"false\"><div><p>t</p></div></li></ul>",
            "<p>a</p><hr>",
            "<p>a</p><blockquote><p>q</p></blockquote>",
            "<p>a</p><pre><code>x</code></pre>",
            "<p>a</p><div class=\"custom-widget\">w</div>",
        )
        for (html in fixtures) {
            val parsed = NoteDocument.parse(html)
            assertTrue("fixture should end in a block: $html", parsed.last() !is TextSegment)
            val ensured = NoteDocument.ensureTrailingTextSegment(parsed)
            assertEquals(parsed.size + 1, ensured.size)
            assertEquals(parsed, ensured.dropLast(1))
            assertEquals(TextSegment(""), ensured.last())
        }
    }

    @Test
    fun documentEndingInTextIsUnchanged() {
        for (html in listOf(fullNote, "<h2>H</h2><p>tail</p>")) {
            val parsed = NoteDocument.parse(html)
            assertEquals(parsed, NoteDocument.ensureTrailingTextSegment(parsed))
            assertEquals(
                NoteDocument.serialize(parsed),
                NoteDocument.serialize(NoteDocument.ensureTrailingTextSegment(parsed)),
            )
        }
    }

    @Test
    fun openSaveCyclesNeverAccumulateEmptyParagraphs() {
        // Simulated editor cycle: load (ensure) -> save (strip).
        fun cycle(html: String): String = NoteDocument.serialize(
            NoteDocument.stripTrailingEmptyTextSegments(
                NoteDocument.ensureTrailingTextSegment(NoteDocument.parse(html)),
            ),
        )
        val starts = listOf(
            "<p>a</p><p><img src=\"nota-attachment://abc\" alt=\"d\"></p>",
            "<p>a</p><table><tbody><tr><td>1</td></tr></tbody></table>",
        )
        for (start in starts) {
            var html = start
            repeat(3) { html = cycle(html) }
            // Stable from the first cycle, and equal to the plain
            // parse->serialize storage form (the VM baseline contract).
            assertEquals(
                NoteDocument.serialize(NoteDocument.parse(start)),
                html,
            )
            val working = NoteDocument.ensureTrailingTextSegment(NoteDocument.parse(html))
            assertTrue(working.last() is TextSegment)
            assertTrue(
                NoteDocument.isEmptyTextSegment(working.last()),
            )
        }
    }

    @Test
    fun blankAndEmptyOnlyDocumentsStaySingleEmptyTextSegment() {
        assertEquals(
            listOf(TextSegment("")),
            NoteDocument.ensureTrailingTextSegment(NoteDocument.parse("")),
        )
        assertEquals(
            listOf(TextSegment("")),
            NoteDocument.ensureTrailingTextSegment(NoteDocument.parse("<p></p>")),
        )
    }

    @Test
    fun existingTrailingEmptyParagraphsCollapseToOne() {
        val parsed = NoteDocument.parse(
            "<table><tbody><tr><td>1</td></tr></tbody></table><p></p>",
        )
        val ensured = NoteDocument.ensureTrailingTextSegment(parsed)
        assertTrue(ensured.last() is TextSegment)
        assertEquals(1, ensured.count { it is TextSegment })

        val manual = listOf(
            ImageSegment("x"),
            TextSegment(""),
            TextSegment("<p></p>"),
        )
        assertEquals(
            listOf(ImageSegment("x"), TextSegment("")),
            NoteDocument.ensureTrailingTextSegment(manual),
        )
    }

    @Test
    fun typedTrailingSegmentSurvivesSaveStrip() {
        val working = listOf(ImageSegment("x"), TextSegment("<p>after</p>"))
        assertEquals(working, NoteDocument.stripTrailingEmptyTextSegments(working))
        val stored = NoteDocument.serialize(working)
        assertTrue(stored.endsWith("<p>after</p>"))
        assertEquals(
            working,
            NoteDocument.ensureTrailingTextSegment(NoteDocument.parse(stored)),
        )
    }

    @Test
    fun stripRemovesOnlyTrailingEmpties() {
        val middle = listOf(
            TextSegment("<p>a</p>"),
            TextSegment(""),
            ImageSegment("x"),
        )
        assertEquals(middle, NoteDocument.stripTrailingEmptyTextSegments(middle))
        // An empty LIST inside a text segment is user content, never
        // classified as an empty paragraph.
        val emptyList = TextSegment("<ul><li><p></p></li></ul>")
        assertTrue(!NoteDocument.isEmptyTextSegment(emptyList))
        assertTrue(NoteDocument.isEmptyTextSegment(TextSegment("<p><br></p>")))
        assertTrue(NoteDocument.isEmptyTextSegment(TextSegment("<p>&nbsp;</p>")))
        assertTrue(!NoteDocument.isEmptyTextSegment(TextSegment("<p>x</p>")))
    }

    // ------------------------------------------------ Phase 1.5 fix 3
    // ImageSegment.widthPercent, serialised as width="N%".

    @Test
    fun imageWidthParsesAndRoundTripsByteForByte() {
        val html = "<p><img src=\"nota-attachment://abc\" alt=\"diagram\" width=\"50%\"></p>"
        val segments = NoteDocument.parse(html)
        assertEquals(
            listOf(ImageSegment("nota-attachment://abc", "diagram", 50)),
            segments,
        )
        assertEquals(html, NoteDocument.serialize(segments))
        assertEquals(segments, NoteDocument.parse(NoteDocument.serialize(segments)))
    }

    @Test
    fun imageWithoutWidthDefaultsTo100AndSerializesWithoutAttribute() {
        val html = "<p><img src=\"x\" alt=\"y\"></p>"
        val segments = NoteDocument.parse(html)
        assertEquals(100, (segments.single() as ImageSegment).widthPercent)
        assertEquals(html, NoteDocument.serialize(segments))
    }

    @Test
    fun imageWidthClampsToRange() {
        fun widthOf(html: String) =
            (NoteDocument.parse(html).single() as ImageSegment).widthPercent
        assertEquals(25, widthOf("<p><img src=\"x\" width=\"10%\"></p>"))
        assertEquals(100, widthOf("<p><img src=\"x\" width=\"150%\"></p>"))
        assertEquals(25, widthOf("<p><img src=\"x\" width=\"25%\"></p>"))
        // 150% clamps to the default, so the attribute disappears.
        assertEquals(
            "<p><img src=\"x\" alt=\"\"></p>",
            NoteDocument.serialize(NoteDocument.parse("<p><img src=\"x\" width=\"150%\"></p>")),
        )
        assertEquals(
            "<p><img src=\"x\" alt=\"\" width=\"25%\"></p>",
            NoteDocument.serialize(NoteDocument.parse("<p><img src=\"x\" width=\"10%\"></p>")),
        )
    }

    @Test
    fun imageWidthIgnoresPixelAndGarbageValues() {
        fun widthOf(html: String) =
            (NoteDocument.parse(html).single() as ImageSegment).widthPercent
        assertEquals(100, widthOf("<p><img src=\"x\" width=\"600\"></p>"))
        assertEquals(100, widthOf("<p><img src=\"x\" width=\"abc%\"></p>"))
        assertEquals(100, widthOf("<p><img src=\"x\" width=\"\"></p>"))
    }

    @Test
    fun imageWidthFromStyleFallbackAndAttributePrecedence() {
        fun widthOf(html: String) =
            (NoteDocument.parse(html).single() as ImageSegment).widthPercent
        assertEquals(40, widthOf("<p><img src=\"x\" style=\"width: 40%;\"></p>"))
        assertEquals(75, widthOf("<p><img src=\"x\" style=\"color: red; width:75%\"></p>"))
        assertEquals(
            50,
            widthOf("<p><img src=\"x\" width=\"50%\" style=\"width: 75%\"></p>"),
        )
        // The fallback is rescued INTO the canonical attribute form.
        assertEquals(
            "<p><img src=\"x\" alt=\"\" width=\"40%\"></p>",
            NoteDocument.serialize(
                NoteDocument.parse("<p><img src=\"x\" style=\"width: 40%;\"></p>"),
            ),
        )
    }

    @Test
    fun imageWidthStyleFallbackIgnoresMaxAndMinWidth() {
        // A max-/min-width constraint is not a fixed display width: the
        // fallback must not match the "width:" inside those properties
        // (Codex review, PR #6).
        fun widthOf(html: String) =
            (NoteDocument.parse(html).single() as ImageSegment).widthPercent
        assertEquals(100, widthOf("<p><img src=\"x\" style=\"max-width: 50%\"></p>"))
        assertEquals(100, widthOf("<p><img src=\"x\" style=\"min-width: 30%\"></p>"))
        assertEquals(
            60,
            widthOf("<p><img src=\"x\" style=\"max-width: 90%; width: 60%\"></p>"),
        )
    }

    @Test
    fun imageWidthSurvivesAllThreeParsePaths() {
        // Bare <img>.
        val bare = NoteDocument.parse("<img src=\"x\" width=\"50%\">")
        assertEquals(50, (bare.single() as ImageSegment).widthPercent)
        // <img> split out of a paragraph, surrounding text intact.
        val split = NoteDocument.parse("<p>before <img src=\"x\" width=\"30%\"> after</p>")
        val image = split.filterIsInstance<ImageSegment>().single()
        assertEquals(30, image.widthPercent)
        assertTrue(split.first() is TextSegment)
        assertTrue(split.last() is TextSegment)
        // <figure> with a caption.
        val figure = NoteDocument.parse(
            "<figure><img src=\"x\" width=\"60%\"><figcaption>cap</figcaption></figure>",
        )
        assertEquals(60, (figure.first() as ImageSegment).widthPercent)
        assertTrue(figure.last() is TextSegment)
    }

    @Test
    fun imageWidthSingleQuotedAttribute() {
        val segments = NoteDocument.parse("<p><img src='x' width='45%'></p>")
        assertEquals(45, (segments.single() as ImageSegment).widthPercent)
    }
}
