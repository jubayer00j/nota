package app.nota.data.html

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // A task list nested inside a regular bullet item, in the
        // shape that reaches the normaliser: the nested list is a
        // task list by its <ul data-type="taskList"> attribute
        // (its items are plain <li> — with canonical
        // data-type="taskItem" items the OUTER list is classified
        // as a task list by parse's isTaskList heuristic before
        // normalisation is ever consulted). The OUTER list is
        // normalised to the bare-item dialect, but the nested task
        // list must survive byte-for-byte — its items' <div><p>
        // scaffolding is not the editor-hostile shape
        // normalisation exists to remove. (Codex P2, Phase 2.5.)
        val taskList = "<ul data-type=\"taskList\">" +
            "<li><div><p>Revise optics</p></div></li>" +
            "<li><div><p>Revise thermo</p></div></li></ul>"
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

    // ------------------------------------------------ Phase 2.8 (E1)
    // Tap-anywhere caret: scratch padding grown inside the last text
    // segment is trimmed at paragraph level on save; spacing with
    // content below it is real and persists.

    @Test
    fun tapPaddingInNonEmptyLastSegmentDoesNotPersist() {
        // He taps deep below "Hello" and leaves without typing: the
        // working segment carries the padding, the storage form must
        // not — this is the case the Phase 1.5 segment-level strip
        // could not see (the segment is not empty, it holds "Hello").
        val working = listOf(TextSegment("<p>Hello</p><p></p><p></p><p></p>"))
        assertEquals(
            listOf(TextSegment("<p>Hello</p>")),
            NoteDocument.stripTrailingEmptyTextSegments(working),
        )
    }

    @Test
    fun tapPaddingPersistsWhenTypedBelowIt() {
        // Same padding, but he typed in the last paragraph: the
        // empties above his text are real spacing now.
        val working = listOf(TextSegment("<p>Hello</p><p></p><p></p><p>Deep</p>"))
        assertEquals(working, NoteDocument.stripTrailingEmptyTextSegments(working))
    }

    @Test
    fun paragraphTrimStopsAtListsAndStructure() {
        // Padding after a list goes; the list itself is content.
        val withList = listOf(TextSegment("<p>a</p><ul><li>x</li></ul><p></p>"))
        assertEquals(
            listOf(TextSegment("<p>a</p><ul><li>x</li></ul>")),
            NoteDocument.stripTrailingEmptyTextSegments(withList),
        )
        val listOnly = listOf(TextSegment("<ul><li></li></ul>"))
        assertEquals(listOnly, NoteDocument.stripTrailingEmptyTextSegments(listOnly))
        // A paragraph holding an image is content even when its text
        // is blank.
        val withImage = "<p>a</p><p><img src=\"x\" alt=\"\"></p>"
        assertEquals(withImage, NoteDocument.trimTrailingEmptyParagraphs(withImage))
    }

    @Test
    fun paragraphTrimCoversHeadingsBrAndNbspForms() {
        assertEquals(
            "<p>a</p>",
            NoteDocument.trimTrailingEmptyParagraphs("<p>a</p><h2></h2>"),
        )
        assertEquals(
            "<p>a</p>",
            NoteDocument.trimTrailingEmptyParagraphs("<p>a</p><p><br></p><p>&nbsp;</p>"),
        )
        // Middle empties with content after them are byte-stable.
        val middle = "<p></p><p>a</p><p></p><p>b</p>"
        assertEquals(middle, NoteDocument.trimTrailingEmptyParagraphs(middle))
        // Nothing to trim: returned unchanged.
        val plain = "<p>a</p>"
        assertEquals(plain, NoteDocument.trimTrailingEmptyParagraphs(plain))
    }

    @Test
    fun breakMarkerPaddingDoesNotPersist() {
        // The rich editor's HTML dialect: trailing empty paragraphs
        // serialise as bare <br> markers (probe-verified). This is
        // the exact stored form of "tapped deep below Hello, left
        // without typing" — it must trim to the content alone.
        val working = listOf(TextSegment("<p>Hello</p><br><br>"))
        assertEquals(
            listOf(TextSegment("<p>Hello</p>")),
            NoteDocument.stripTrailingEmptyTextSegments(working),
        )
        assertEquals(
            "<p>Hello</p>",
            NoteDocument.trimTrailingEmptyParagraphs("<p>Hello</p><br>"),
        )
        // Mixed forms (break markers after empty paragraphs) all go.
        assertEquals(
            "<p>Hello</p>",
            NoteDocument.trimTrailingEmptyParagraphs("<p>Hello</p><p></p><br>"),
        )
    }

    @Test
    fun breakMarkersTypedBelowPersistAndInnerBreaksAreContent() {
        // Typed below the padding: the <br> markers are mid-document
        // spacing now — the exact fixed-point form the editor reloads.
        val typed = listOf(TextSegment("<p>Hello</p><br><br><p>Deep</p>"))
        assertEquals(typed, NoteDocument.stripTrailingEmptyTextSegments(typed))
        // A <br> inside a paragraph is a line break, never padding.
        val inner = "<p>line<br>more</p>"
        assertEquals(inner, NoteDocument.trimTrailingEmptyParagraphs(inner))
        val trailingInner = "<p>word<br></p>"
        assertEquals(trailingInner, NoteDocument.trimTrailingEmptyParagraphs(trailingInner))
    }

    @Test
    fun tapPaddingAfterTrailingBlockIsDroppedWithItsSegment() {
        // The document ends in an image; the caret segment below it
        // holds only tap padding — segment-level strip takes it all.
        val working = listOf(ImageSegment("x"), TextSegment("<p></p><p></p>"))
        assertEquals(
            listOf(ImageSegment("x")),
            NoteDocument.stripTrailingEmptyTextSegments(working),
        )
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

    // ---- Phase 6 polish: structural task-list classification ------
    // isTaskList used to substring-search the list's inner HTML for
    // "data-checked"/"taskItem", so VISIBLE TEXT containing those
    // words turned a plain list into a task list on save.

    @Test
    fun plainListWithTaskWordsInTextStaysPlain() {
        val html = "<ul><li><p>Document the data-checked attribute</p></li>" +
            "<li><p>taskItem notes</p></li></ul>"
        val segments = NoteDocument.parse(html)
        assertTrue(segments.none { it is TaskListSegment })
        val saved = NoteDocument.serialize(segments)
        assertFalse(saved.contains("data-type=\"taskList\""))
    }

    @Test
    fun structuralTaskListWithoutUlDataTypeStillParses() {
        // Tiptap variants where the <ul> carries no data-type but the
        // items themselves carry the structural markers.
        val html = "<ul><li data-type=\"taskItem\" data-checked=\"true\"><p>Done one</p></li>" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><p>Open one</p></li></ul>"
        val segments = NoteDocument.parse(html)
        val list = segments.filterIsInstance<TaskListSegment>().single()
        assertEquals(2, list.items.size)
        assertTrue(list.items[0].checked)
        assertFalse(list.items[1].checked)
    }

    @Test
    fun taskItemCheckedComesFromAttributeNotContent() {
        // Genuine task list (ul data-type). The item's CONTENT says
        // "checked" and carries a literal <input>, but the li's own
        // data-checked says false — the old content fallback
        // ("checked" + "<input" substrings) would have ticked it.
        val html = "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"false\">" +
            "<p>checked <input type=\"text\"> in text</p></li></ul>"
        val segments = NoteDocument.parse(html)
        val list = segments.filterIsInstance<TaskListSegment>().single()
        assertFalse(list.items.single().checked)
    }

    // ---- Phase 6 polish (N5): label edits preserve formatting -----

    @Test
    fun replaceTaskItemText_keepsStrongWrapper() {
        assertEquals(
            "<p><strong>New label</strong></p>",
            NoteDocument.replaceTaskItemText("<p><strong>Old label</strong></p>", "New label"),
        )
    }

    @Test
    fun replaceTaskItemText_keepsLinkAroundNewText() {
        val out = NoteDocument.replaceTaskItemText(
            "<p><a href=\"https://example.com/page\">Old label</a></p>",
            "New label",
        )
        assertEquals("<p><a href=\"https://example.com/page\">New label</a></p>", out)
    }

    @Test
    fun replaceTaskItemText_plainAndEscaping() {
        assertEquals(
            "<p>New</p>",
            NoteDocument.replaceTaskItemText("<p>Old</p>", "New"),
        )
        assertEquals(
            "<p>A &amp; B</p>",
            NoteDocument.replaceTaskItemText("<p>Old</p>", "A & B"),
        )
        assertEquals(
            "<p>New</p>",
            NoteDocument.replaceTaskItemText("<p></p>", "New"),
        )
        assertEquals(
            "<strong>New</strong>",
            NoteDocument.replaceTaskItemText("<strong>Old</strong>", "New"),
        )
    }

    @Test
    fun replaceTaskItemText_mixedMarkupConsolidatesIntoFirstRun() {
        // The documented fidelity limit: elements survive byte-
        // identical; the new label consolidates into the first text
        // run (its formatting), later runs are emptied.
        assertEquals(
            "<p><strong>Buy oat milk</strong></p>",
            NoteDocument.replaceTaskItemText("<p><strong>Buy</strong> milk</p>", "Buy oat milk"),
        )
    }
    @Test
    fun entitiesDecodeInAttributesAndCodeText() {
        // Attribute values and code text are decoded where raw
        // markup becomes data: the full HTML5 set, exactly once.
        val image = NoteDocument.parse(
            "<p><img src=\"nota-attachment://abc\" alt=\"&copy; cat &eacute;\"></p>",
        ).filterIsInstance<ImageSegment>().single()
        assertEquals("\u00A9 cat \u00E9", image.alt)
        val code = NoteDocument.parse(
            "<pre><code>&copy; &lt;tag&gt; &#169;</code></pre>",
        ).filterIsInstance<CodeSegment>().single()
        assertEquals("\u00A9 <tag> \u00A9", code.text)
    }

    @Test
    fun escapedEntitiesStayLiteralText() {
        // "&amp;copy;" is the seven literal characters "&copy;"
        // the author typed \u2014 decoding never runs twice.
        val image = NoteDocument.parse(
            "<p><img src=\"nota-attachment://abc\" alt=\"&amp;copy;\"></p>",
        ).filterIsInstance<ImageSegment>().single()
        assertEquals("&copy;", image.alt)
    }

    @Test
    fun ampersandHeavyTextDecodesLiterallyAndLinearly() {
        // Thousands of '&' with no reference in sight must
        // pass through untouched (and the bounded lookahead
        // keeps the scan linear — this input is the shape
        // that used to go quadratic), while a real reference
        // at the very end still decodes.
        val amps = "&".repeat(5000)
        assertEquals(amps + "\u00A9", HtmlEntities.decode(amps + "&copy;"))
        assertEquals("a & b &nope c", HtmlEntities.decode("a & b &nope c"))
    }

    // -------------------------------- Editor Refinement #2: conversions

    @Test
    fun plainParagraphClassification() {
        assertTrue(NoteDocument.isPlainParagraphHtml("<p>Hello</p>"))
        assertTrue(NoteDocument.isPlainParagraphHtml("<p class=\"x\">Hello</p>"))
        assertTrue(NoteDocument.isPlainParagraphHtml("  <p><strong>Bold</strong></p>  "))
        assertFalse(NoteDocument.isPlainParagraphHtml(""))
        assertFalse(NoteDocument.isPlainParagraphHtml("<h1>Title</h1>"))
        assertFalse(NoteDocument.isPlainParagraphHtml("<ul><li>Item</li></ul>"))
        assertFalse(NoteDocument.isPlainParagraphHtml("<p>One</p><p>Two</p>"))
        assertFalse(NoteDocument.isPlainParagraphHtml("Bare text"))
    }

    @Test
    fun makeTask_middleParagraph_splitsTextAroundANewList() {
        val segments = listOf(
            TextSegment("<p>One</p><p>Two</p><p>Three</p>"),
        )
        val conversion = NoteDocument.paragraphToTask(
            segments, 0, "<p>One</p>", "<p>Two</p>", "<p>Three</p>",
        )!!
        assertEquals(
            listOf(
                TextSegment("<p>One</p>"),
                TaskListSegment(listOf(TaskItem("<p>Two</p>", checked = false))),
                TextSegment("<p>Three</p>"),
            ),
            conversion.segments,
        )
        assertEquals(1, conversion.convertedIndex)
        assertEquals(0, conversion.convertedOrdinal)
        // The halves both derive from the split segment; the list
        // is minted by the conversion.
        assertEquals(listOf(0, -1, 0), conversion.sourceIndices)
    }

    @Test
    fun makeTask_firstParagraph_dropsTheEmptyBeforeHalf() {
        val segments = listOf(TextSegment("<p>One</p><p>Two</p>"))
        val conversion = NoteDocument.paragraphToTask(
            segments, 0, "", "<p>One</p>", "<p>Two</p>",
        )!!
        assertEquals(
            listOf(
                TaskListSegment(listOf(TaskItem("<p>One</p>", checked = false))),
                TextSegment("<p>Two</p>"),
            ),
            conversion.segments,
        )
        assertEquals(0, conversion.convertedIndex)
    }

    @Test
    fun makeTask_lastParagraph_dropsTheEmptyAfterHalf() {
        val segments = listOf(TextSegment("<p>One</p><p>Two</p>"))
        val conversion = NoteDocument.paragraphToTask(
            segments, 0, "<p>One</p>", "<p>Two</p>", "",
        )!!
        assertEquals(
            listOf(
                TextSegment("<p>One</p>"),
                TaskListSegment(listOf(TaskItem("<p>Two</p>", checked = false))),
            ),
            conversion.segments,
        )
        assertEquals(1, conversion.convertedIndex)
    }

    @Test
    fun makeTask_onlyParagraph_becomesAOneItemList() {
        val segments = listOf(TextSegment("<p>Solo</p>"))
        val conversion = NoteDocument.paragraphToTask(
            segments, 0, "", "<p>Solo</p>", "",
        )!!
        assertEquals(
            listOf(TaskListSegment(listOf(TaskItem("<p>Solo</p>", checked = false)))),
            conversion.segments,
        )
        assertEquals(0, conversion.convertedIndex)
    }

    @Test
    fun makeTask_appendsIntoTheTaskListAbove() {
        val above = TaskListSegment(listOf(TaskItem("<p>Existing</p>", checked = true)))
        val segments = listOf(above, TextSegment("<p>One</p><p>Two</p>"))
        val conversion = NoteDocument.paragraphToTask(
            segments, 1, "", "<p>One</p>", "<p>Two</p>",
        )!!
        assertEquals(
            listOf(
                TaskListSegment(
                    listOf(
                        TaskItem("<p>Existing</p>", checked = true),
                        TaskItem("<p>One</p>", checked = false),
                    ),
                ),
                TextSegment("<p>Two</p>"),
            ),
            conversion.segments,
        )
        assertEquals(0, conversion.convertedIndex)
        assertEquals(1, conversion.convertedOrdinal)
        // The joined list derives from the list above.
        assertEquals(0, conversion.sourceIndices[0])
    }

    @Test
    fun makeTask_prependsIntoTheTaskListBelow() {
        val below = TaskListSegment(listOf(TaskItem("<p>Existing</p>", checked = false)))
        val segments = listOf(TextSegment("<p>One</p><p>Two</p>"), below)
        val conversion = NoteDocument.paragraphToTask(
            segments, 0, "<p>One</p>", "<p>Two</p>", "",
        )!!
        assertEquals(
            listOf(
                TextSegment("<p>One</p>"),
                TaskListSegment(
                    listOf(
                        TaskItem("<p>Two</p>", checked = false),
                        TaskItem("<p>Existing</p>", checked = false),
                    ),
                ),
            ),
            conversion.segments,
        )
        assertEquals(1, conversion.convertedIndex)
        assertEquals(0, conversion.convertedOrdinal)
        assertEquals(1, conversion.sourceIndices[1])
    }

    @Test
    fun makeTask_betweenTwoLists_fusesAllThree() {
        val above = TaskListSegment(listOf(TaskItem("<p>A</p>", checked = false)))
        val below = TaskListSegment(listOf(TaskItem("<p>B</p>", checked = true)))
        val segments = listOf(above, TextSegment("<p>Middle</p>"), below)
        val conversion = NoteDocument.paragraphToTask(
            segments, 1, "", "<p>Middle</p>", "",
        )!!
        assertEquals(
            listOf(
                TaskListSegment(
                    listOf(
                        TaskItem("<p>A</p>", checked = false),
                        TaskItem("<p>Middle</p>", checked = false),
                        TaskItem("<p>B</p>", checked = true),
                    ),
                ),
            ),
            conversion.segments,
        )
        assertEquals(0, conversion.convertedIndex)
        assertEquals(1, conversion.convertedOrdinal)
    }

    @Test
    fun makeTask_refusesNonParagraphsAndNonTextSegments() {
        val segments = listOf(TextSegment("<h1>Title</h1><p>Body</p>"))
        assertEquals(
            null,
            NoteDocument.paragraphToTask(segments, 0, "", "<h1>Title</h1>", "<p>Body</p>"),
        )
        assertEquals(
            null,
            NoteDocument.paragraphToTask(
                segments, 0, "", "<ul><li>Item</li></ul>", "<p>Body</p>",
            ),
        )
        val withBlock = listOf(DividerSegment, TextSegment("<p>Body</p>"))
        assertEquals(
            null,
            NoteDocument.paragraphToTask(withBlock, 0, "", "<p>Body</p>", ""),
        )
    }

    @Test
    fun makeTask_serialisesThroughTheExistingWireFormat() {
        val segments = listOf(TextSegment("<p>One</p><p>Two</p>"))
        val conversion = NoteDocument.paragraphToTask(
            segments, 0, "<p>One</p>", "<p>Two</p>", "",
        )!!
        // The conversion produces ordinary segments: the stored
        // form is the canonical task-list HTML, unchanged.
        assertEquals(
            "<p>One</p><ul data-type=\"taskList\">" +
                "<li data-type=\"taskItem\" data-checked=\"false\">" +
                "<div><p>Two</p></div></li></ul>",
            NoteDocument.serialize(conversion.segments),
        )
        // And it parses straight back to the same segments.
        assertEquals(
            conversion.segments,
            NoteDocument.parse(NoteDocument.serialize(conversion.segments)),
        )
    }

    @Test
    fun makeText_soleItem_becomesAStandaloneParagraph() {
        val segments = listOf(
            TaskListSegment(listOf(TaskItem("<p>Only</p>", checked = true))),
        )
        val conversion = NoteDocument.taskItemToText(segments, 0, 0)!!
        assertEquals(listOf(TextSegment("<p>Only</p>")), conversion.segments)
        assertEquals(0, conversion.convertedIndex)
        assertEquals(0, conversion.convertedOrdinal)
    }

    @Test
    fun makeText_itemWithTrailingContent_convertsAllOfIt() {
        // Codex P2 (PR #41): a childless item can still carry
        // trailing content (it followed a nested list that was
        // removed) — "Make text" must convert ALL of it, not just
        // the leading part.
        val segments = listOf(
            TaskListSegment(
                listOf(
                    TaskItem(
                        "<p>Before</p>",
                        checked = false,
                        contentAfterHtml = "<p>After</p>",
                    ),
                ),
            ),
        )
        val conversion = NoteDocument.taskItemToText(segments, 0, 0)!!
        assertEquals(
            listOf(TextSegment("<p>Before</p><p>After</p>")),
            conversion.segments,
        )
    }

    @Test
    fun makeText_middleItem_splitsTheListAroundTheParagraph() {
        val segments = listOf(
            TaskListSegment(
                listOf(
                    TaskItem("<p>A</p>", checked = false),
                    TaskItem("<p>B</p>", checked = true),
                    TaskItem("<p>C</p>", checked = false),
                ),
            ),
        )
        val conversion = NoteDocument.taskItemToText(segments, 0, 1)!!
        assertEquals(
            listOf(
                TaskListSegment(listOf(TaskItem("<p>A</p>", checked = false))),
                TextSegment("<p>B</p>"),
                TaskListSegment(listOf(TaskItem("<p>C</p>", checked = false))),
            ),
            conversion.segments,
        )
        assertEquals(1, conversion.convertedIndex)
        // Both list halves derive from the source list.
        assertEquals(listOf(0, -1, 0), conversion.sourceIndices)
    }

    @Test
    fun makeText_firstItem_mergesIntoTheTextAbove() {
        val segments = listOf(
            TextSegment("<p>Intro</p>"),
            TaskListSegment(
                listOf(
                    TaskItem("<p>First</p>", checked = false),
                    TaskItem("<p>Rest</p>", checked = false),
                ),
            ),
        )
        val conversion = NoteDocument.taskItemToText(segments, 1, 0)!!
        assertEquals(
            listOf(
                TextSegment("<p>Intro</p><p>First</p>"),
                TaskListSegment(listOf(TaskItem("<p>Rest</p>", checked = false))),
            ),
            conversion.segments,
        )
        assertEquals(0, conversion.convertedIndex)
        // The caret ordinal: the converted paragraph follows the
        // one paragraph the text above already held.
        assertEquals(1, conversion.convertedOrdinal)
        assertEquals(0, conversion.sourceIndices[0])
    }

    @Test
    fun makeText_lastItem_mergesIntoTheTextBelow() {
        val segments = listOf(
            TaskListSegment(
                listOf(
                    TaskItem("<p>Rest</p>", checked = false),
                    TaskItem("<p>Last</p>", checked = false),
                ),
            ),
            TextSegment("<p>Outro</p>"),
        )
        val conversion = NoteDocument.taskItemToText(segments, 0, 1)!!
        assertEquals(
            listOf(
                TaskListSegment(listOf(TaskItem("<p>Rest</p>", checked = false))),
                TextSegment("<p>Last</p><p>Outro</p>"),
            ),
            conversion.segments,
        )
        assertEquals(1, conversion.convertedIndex)
        assertEquals(0, conversion.convertedOrdinal)
    }

    @Test
    fun makeText_soleItemBetweenTexts_mergesAllThree() {
        val segments = listOf(
            TextSegment("<p>Above</p>"),
            TaskListSegment(listOf(TaskItem("<p>Mid</p>", checked = false))),
            TextSegment("<p>Below</p>"),
        )
        val conversion = NoteDocument.taskItemToText(segments, 1, 0)!!
        assertEquals(
            listOf(TextSegment("<p>Above</p><p>Mid</p><p>Below</p>")),
            conversion.segments,
        )
        assertEquals(0, conversion.convertedIndex)
        assertEquals(1, conversion.convertedOrdinal)
    }

    @Test
    fun makeText_nonFirstItem_doesNotMergeAcrossTheListAbove() {
        // The item is NOT the list's first: the list's own first
        // item stands between it and the text above, so merging
        // would reorder the document. It stands alone instead.
        val segments = listOf(
            TextSegment("<p>Intro</p>"),
            TaskListSegment(
                listOf(
                    TaskItem("<p>First</p>", checked = false),
                    TaskItem("<p>Second</p>", checked = false),
                ),
            ),
        )
        val conversion = NoteDocument.taskItemToText(segments, 1, 1)!!
        assertEquals(
            listOf(
                TextSegment("<p>Intro</p>"),
                TaskListSegment(listOf(TaskItem("<p>First</p>", checked = false))),
                TextSegment("<p>Second</p>"),
            ),
            conversion.segments,
        )
        assertEquals(2, conversion.convertedIndex)
    }

    @Test
    fun makeText_multiParagraphItem_keepsEveryParagraph() {
        val segments = listOf(
            TaskListSegment(
                listOf(TaskItem("<p>Line one</p><p>Line two</p>", checked = false)),
            ),
        )
        val conversion = NoteDocument.taskItemToText(segments, 0, 0)!!
        assertEquals(
            listOf(TextSegment("<p>Line one</p><p>Line two</p>")),
            conversion.segments,
        )
    }

    @Test
    fun makeText_bareItemContent_gainsItsParagraph() {
        val segments = listOf(
            TaskListSegment(listOf(TaskItem("Just text", checked = false))),
        )
        val conversion = NoteDocument.taskItemToText(segments, 0, 0)!!
        assertEquals(listOf(TextSegment("<p>Just text</p>")), conversion.segments)
    }

    @Test
    fun makeText_refusesBadCoordinates() {
        val segments = listOf(TextSegment("<p>Body</p>"))
        assertEquals(null, NoteDocument.taskItemToText(segments, 0, 0))
        val withList = listOf(
            TaskListSegment(listOf(TaskItem("<p>A</p>", checked = false))),
        )
        assertEquals(null, NoteDocument.taskItemToText(withList, 0, 5))
        assertEquals(null, NoteDocument.taskItemToText(withList, 3, 0))
    }

    @Test
    fun chartDivV2_survivesParseSerializeByteIdentically() {
        // Charts PR-A's hard invariant: rendering must never cost
        // stored bytes. A v2 chart div (single-quoted JSON attrs,
        // exactly what Noma 1.x writes) round-trips byte-for-byte.
        val div = "<div data-type=\"noma-chart\" data-version=\"2\" " +
            "data-chart-type=\"bar\" data-title=\"Sales\" data-subtitle=\"\" " +
            "data-categories='[\"Jan\",\"Feb\",\"Mar\"]' " +
            "data-series='[{\"id\":\"s1\",\"name\":\"Revenue\",\"values\":[10,20,30]," +
            "\"color\":\"oklch(0.578 0.192 260)\"}]' " +
            "data-options='{\"showLegend\":true,\"legendPosition\":\"bottom\"}'></div>"
        val body = "<p>Before</p>$div<p>After</p>"
        val segments = NoteDocument.parse(body)
        assertTrue(segments.any { it is ChartSegment })
        assertEquals(body, NoteDocument.serialize(segments))
        // And the parse of the serialisation is stable too.
        assertEquals(body, NoteDocument.serialize(NoteDocument.parse(body)))
    }

    @Test
    fun chartDivV1_survivesParseSerializeByteIdentically() {
        val div = "<div data-type=\"noma-chart\" data-chart-type=\"pie\" " +
            "data-title=\"Share\" data-chart='[{\"name\":\"A\",\"value\":3}]'></div>"
        val body = "<p>x</p>$div"
        val segments = NoteDocument.parse(body)
        assertTrue(segments.any { it is ChartSegment })
        assertEquals(body, NoteDocument.serialize(segments))
    }

    @Test
    fun leafParagraphCount_countsBlocksAndListItems() {
        assertEquals(0, NoteDocument.leafParagraphCount(""))
        assertEquals(1, NoteDocument.leafParagraphCount("<p>One</p>"))
        assertEquals(
            3,
            NoteDocument.leafParagraphCount("<p>One</p><h1>Two</h1><p>Three</p>"),
        )
        assertEquals(
            3,
            NoteDocument.leafParagraphCount("<ul><li>A</li><li>B</li><li>C</li></ul>"),
        )
        assertEquals(
            3,
            NoteDocument.leafParagraphCount("<p>Intro</p><ul><li>A</li><li>B</li></ul>"),
        )
    }

}
