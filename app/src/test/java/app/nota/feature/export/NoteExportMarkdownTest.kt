package app.nota.feature.export

import app.nota.data.html.CodeSegment
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
        assertTrue(out.contains("[spaced](<https://example.test/a b>)"))
        assertTrue(out.contains("[plain](https://example.test/plain)"))
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
}
