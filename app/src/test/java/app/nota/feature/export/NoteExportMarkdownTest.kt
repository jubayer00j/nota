package app.nota.feature.export

import app.nota.data.html.CodeSegment
import app.nota.data.html.FileSegment
import app.nota.data.html.OpaqueSegment
import app.nota.data.html.ImageSegment
import app.nota.data.html.TableSegment
import app.nota.data.html.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-note Export #1 — the Markdown converter (1.x's
 * noteToMarkdown semantics over Nota's segment model).
 */
class NoteExportMarkdownTest {

    private fun md() = NoteExport.noteToMarkdown(
        EXPORT_FIXTURE_TITLE, exportFixture(), exportResolver,
    )

    @Test
    fun titleBecomesEscapedH1() {
        val out = md()
        assertTrue(out.startsWith("# Field notes & \\<draft>\n\n"))
        assertTrue(out.endsWith("\n"))
    }

    @Test
    fun headingsAndInlineMarks() {
        val out = md()
        assertTrue(out.contains("## Shopping & plans"))
        assertTrue(
            out.contains(
                "Bring **bold**, *italic*, ~~struck~~, `x < y` and a " +
                    "[link](https://example.com/list).",
            ),
        )
    }

    @Test
    fun noteLinksBecomeTheirLabelText() {
        val out = md()
        assertTrue(out.contains("See Groceries and the house list."))
        assertFalse(out.contains("[["))
    }

    @Test
    fun inlineImagesResolveOrDegrade() {
        val out = md()
        assertTrue(out.contains("![Inline cat]($EXPORT_DATA_URL)"))
        // The unresolved inline image degrades to the placeholder.
        assertTrue(out.contains("\n[Image]\n"))
    }

    @Test
    fun nestedAndStartedLists() {
        val out = md()
        assertTrue(out.contains("- milk\n- bread\n  - rye\n  - white"))
        assertTrue(out.contains("3. third\n4. fourth"))
    }

    @Test
    fun taskItemsCarryTheirState() {
        val out = md()
        assertTrue(out.contains("- [x] Book **flights**\n- [ ] Pack"))
    }

    @Test
    fun tableIsAPipeTableWithEscapedPipes() {
        val out = md()
        assertTrue(
            out.contains("| Item | Qty |\n| --- | --- |\n| Rice \\| Basmati | 2 |"),
        )
    }

    @Test
    fun headerlessTableGetsAnEmptyHeaderRow() {
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TableSegment(
                    rows = listOf(listOf("a", "b"), listOf("c", "d")),
                    headerRow = false,
                ),
            ),
        )
        assertTrue(out.contains("|  |  |\n| --- | --- |\n| a | b |\n| c | d |"))
    }

    @Test
    fun codeWithBackticksUsesALongerFence() {
        val out = md()
        assertTrue(out.contains("````\nval x = \"a ``` b\" // <not> a tag\n````"))
    }

    @Test
    fun codeWithLongBacktickRunsGetsAnEvenLongerFence() {
        // Runs of four and six backticks inside the code would
        // close a three- or four-backtick fence early; the fence
        // must outrun the longest run.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(CodeSegment("before\n````\nmiddle\n``````\nafter")),
        )
        assertTrue(
            out.contains("```````\nbefore\n````\nmiddle\n``````\nafter\n```````"),
        )
    }

    @Test
    fun literalBlockMarkersStayLiteral() {
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(
                    "<p># not a heading</p>" +
                        "<p>> not a quote</p>" +
                        "<p>- not a list</p>" +
                        "<p>+ not a bullet</p>" +
                        "<p>1. not a list either</p>" +
                        "<p>2) also not a list</p>",
                ),
            ),
        )
        assertTrue(out.contains("\\# not a heading"))
        assertTrue(out.contains("\\> not a quote"))
        assertTrue(out.contains("\\- not a list"))
        assertTrue(out.contains("\\+ not a bullet"))
        assertTrue(out.contains("1\\. not a list either"))
        assertTrue(out.contains("2\\) also not a list"))
        assertFalse(out.contains("\n# not a heading"))
    }

    @Test
    fun markersMidLineAreNotEscaped() {
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(TextSegment("<p>a # b and 1. mid and - mid</p>")),
        )
        assertTrue(out.contains("a # b and 1. mid and - mid"))
    }

    @Test
    fun linkDestinationsWithDelimitersUseTheAngleForm() {
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(
                    "<p><a href=\"https://example.test/path)\">unmatched</a> " +
                        "<a href=\"https://en.wikipedia.org/wiki/Foo_(bar)\">wiki</a> " +
                        "<a href=\"https://example.test/a b\">spaced</a> " +
                        "<a href=\"https://example.test/plain\">plain</a></p>",
                ),
            ),
        )
        assertTrue(out.contains("[unmatched](<https://example.test/path)>)"))
        assertTrue(out.contains("[wiki](<https://en.wikipedia.org/wiki/Foo_(bar)>)"))
        // Whitespace is percent-encoded now, so the spaced
        // destination needs no angle form (no parentheses left).
        assertTrue(out.contains("[spaced](https://example.test/a%20b)"))
        assertTrue(out.contains("[plain](https://example.test/plain)"))
    }

    @Test
    fun linkDestinationWhitespaceIsPercentEncoded() {
        // Tab/newline arrive entity-encoded in the attribute and
        // decode to real whitespace: a line ending is invalid
        // even inside the angle form, so whitespace is encoded
        // before the bare/angle decision. Parentheses still force
        // the angle form around the encoded destination.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(
                    "<p><a href=\"https://example.test/a&#9;b\">tab</a> " +
                        "<a href=\"https://example.test/a&#10;b\">newline</a> " +
                        "<a href=\"https://example.test/a b_(c)\">mix</a></p>",
                ),
            ),
        )
        assertTrue(out.contains("[tab](https://example.test/a%09b)"))
        assertTrue(out.contains("[newline](https://example.test/a%0Ab)"))
        assertTrue(out.contains("[mix](<https://example.test/a%20b_(c)>)"))
        assertFalse(out.contains("a b"))
    }

    @Test
    fun imageSourceWhitespaceIsPercentEncoded() {
        // Image sources share mdLinkDestination with links, so
        // they pick up the same whitespace encoding.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                ImageSegment(
                    src = "https://example.com/my pic.png", alt = "Pic",
                ),
            ),
        )
        assertTrue(out.contains("![Pic](https://example.com/my%20pic.png)"))
    }

    @Test
    fun quoteDividerChartFileAndOpaque() {
        val out = md()
        assertTrue(out.contains("> Measure *twice*, cut once."))
        assertTrue(out.contains("[File: tickets.pdf]"))
        assertTrue(out.contains("\n---\n"))
        assertTrue(out.contains("[Bar chart]"))
        assertTrue(out.contains("Embedded words"))
    }

    @Test
    fun standaloneImages() {
        val out = md()
        assertTrue(out.contains("![Cat]($EXPORT_DATA_URL)"))
        assertTrue(out.contains("![Web pic](https://example.com/pic.png)"))
    }

    @Test
    fun markdownSpecialsInBodyTextAreEscaped() {
        val out = NoteExport.noteToMarkdown(
            "A *star* [note]",
            listOf(TextSegment("<p>cost is 5 * 3 and a_b</p>")),
        )
        assertTrue(out.startsWith("# A \\*star\\* \\[note\\]"))
        assertTrue(out.contains("cost is 5 \\* 3 and a\\_b"))
    }

    @Test
    fun inlineCodeWithBackticksGetsALongerSpan() {
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(TextSegment("<p>Run <code>a `b` c</code> now</p>")),
        )
        assertTrue(out.contains("Run ``a `b` c`` now"))
    }

    @Test
    fun underlineAndHighlightSurviveAsHtml() {
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(
                    "<p><u>under</u> and <mark>marked</mark> and " +
                        "<span style=\"color: rgb(179, 38, 30);\">red</span></p>",
                ),
            ),
        )
        assertTrue(out.contains("<u>under</u> and <mark>marked</mark> and red"))
    }

    @Test
    fun emptyDocumentIsJustTheTitle() {
        assertEquals("# Solo\n", NoteExport.noteToMarkdown("Solo", emptyList()))
        assertEquals(
            "# Untitled note\n",
            NoteExport.noteToMarkdown("  ", emptyList()),
        )
    }

    @Test
    fun collectAttachmentIdsDedupesInFirstUseOrder() {
        assertEquals(
            listOf("img1", "missing"),
            NoteExport.collectAttachmentIds(exportFixture()),
        )
    }
    @Test
    fun inlineCodeWithBoundarySpacesIsPadded() {
        // CommonMark strips one space from each end of a code
        // span whose content starts AND ends with a space, so
        // the exporter pads the span to keep the user's spaces.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(TextSegment("<p>Run <code> foo </code> now</p>")),
        )
        assertTrue(out.contains("Run `  foo  ` now"))
        // Ordinary code stays unpadded.
        val plain = NoteExport.noteToMarkdown(
            "T",
            listOf(TextSegment("<p>Run <code>bar</code> now</p>")),
        )
        assertTrue(plain.contains("Run `bar` now"))
    }

    @Test
    fun codeBlockTrailingBlankLinesSurvive() {
        // A code block can end with blank lines; the fence keeps
        // them instead of trimming the block's tail away.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(CodeSegment("first\n\n\n")),
        )
        assertTrue(out.contains("```\nfirst\n\n\n```"))
        // Ordinary code without a trailing newline is unchanged.
        val plain = NoteExport.noteToMarkdown(
            "T",
            listOf(CodeSegment("plain")),
        )
        assertTrue(plain.contains("```\nplain\n```"))
    }

    @Test
    fun typedOrderedListsExportWithNumericMarkers() {
        // CommonMark has no lettered markers: a typed list keeps
        // its start and structure with numeric markers instead
        // of degrading into plain paragraphs.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(
                    "<ol type=\"a\" start=\"3\"><li>third</li><li>fourth</li></ol>",
                ),
            ),
        )
        assertTrue(out.contains("3. third\n4. fourth"))
        assertFalse(out.contains("c. third"))
    }

    @Test
    fun nestedListIndentsPastTheParentMarker() {
        // Under "10. " the content column is 4 — a child indented
        // by only two spaces detaches in strict renderers.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(
                    "<ol start=\"10\"><li>ten<ul><li>child</li></ul></li>" +
                        "<li>eleven</li></ol>",
                ),
            ),
        )
        assertTrue(out.contains("10. ten\n    - child\n11. eleven"))
    }

    @Test
    fun fileNamesAreEscapedInMarkdownPlaceholders() {
        // A file name is user data: unescaped, a name holding
        // "](" would turn the placeholder into a clickable link.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                FileSegment(
                    attachmentId = "f1",
                    fileName = "a](https://example.test) b.pdf",
                ),
            ),
        )
        assertTrue(
            out.contains("[File: a\\](https://example.test) b.pdf]"),
        )
        assertFalse(out.contains("[File: a](https://example.test)"))
    }

    @Test
    fun opaqueTextIsEscapedInMarkdown() {
        // An opaque block's extracted words are plain text: a
        // leading "#" or a link shape must not re-parse as
        // Markdown structure.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                OpaqueSegment(
                    "<p># literal and [label](https://example.test)</p>",
                ),
            ),
        )
        assertTrue(out.contains("\\# literal"))
        assertTrue(out.contains("\\[label\\]"))
        assertFalse(out.contains("\n# literal"))
    }

    @Test
    fun activeAndScriptableLinkDestinationsDropToLabels() {
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(
                    html = "<p><a href=\"javascript:alert(1)\">js</a> and " +
                        "<a href=\"data:image/svg+xml,<svg/>\">svg</a> and " +
                        "<a href=\"https://example.com\">ok</a></p>",
                ),
            ),
        )
        assertTrue(out.contains("js and svg and [ok](https://example.com)"))
        assertFalse(out.contains("javascript:"))
        assertFalse(out.contains("data:image"))
    }

    @Test
    fun setextUnderlineLinesAreEscaped() {
        // A literal line of '=' (or a short dash line) after
        // text is a CommonMark setext heading underline: the
        // paragraph above would re-parse as a heading.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(html = "<p>Words<br>===</p><p>More<br>--</p>"),
            ),
        )
        assertTrue(out.contains("\\==="))
        assertTrue(out.contains("\\--"))
        assertFalse(out.lines().any { it == "===" || it == "--" })
    }

    @Test
    fun imageAltBackslashesAreEscaped() {
        // An alt ending in a backslash would escape the
        // generated closing bracket; doubling keeps the image
        // (and the alt) intact.
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                ImageSegment(
                    src = "https://example.com/cat.png",
                    alt = "Cat\\",
                ),
            ),
        )
        val expected = "![Cat" + "\\".repeat(2) + "](https://example.com/cat.png)"
        assertTrue(out.contains(expected))
    }

    @Test
    fun literalTildesAreEscapedButRealStrikethroughIsNot() {
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(
                    html = "<p>Use ~~word~~ here</p><p><s>gone</s></p>",
                ),
            ),
        )
        assertTrue(out.contains("\\~\\~word\\~\\~"))
        assertTrue(out.contains("~~gone~~"))
    }

    @Test
    fun literalCharacterReferencesAreEscapedInMarkdown() {
        val out = NoteExport.noteToMarkdown(
            "T",
            listOf(
                TextSegment(
                    html = "<p>Type &amp;copy; and &amp;#169; literally, " +
                        "but fish &amp; chips stays plain</p>",
                ),
            ),
        )
        assertTrue(out.contains("&amp;copy;"))
        assertTrue(out.contains("&amp;#169;"))
        assertTrue(out.contains("fish & chips"))
        assertFalse(out.contains(" &copy; "))
    }

}
