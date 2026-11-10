package app.nota.feature.export

import app.nota.data.html.ImageSegment
import app.nota.data.html.OpaqueSegment
import app.nota.data.html.QuoteSegment
import app.nota.data.html.TableCellSpan
import app.nota.data.html.TableSegment
import app.nota.data.html.TaskItem
import app.nota.data.html.TaskListSegment
import app.nota.data.html.TextSegment
import org.junit.Assert.assertEquals
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
        // Benign opaque blocks are preserved as stored.
        assertTrue(out.contains("<div class=\"embed\"><p>Embedded words</p></div>"))
    }

    @Test
    fun chartTitleIsEscapedInHtmlExport() {
        // Charts PR-B (Codex P2): the placeholder carries the
        // chart's title, which is user/imported data. In the HTML
        // export it must be escaped to text — a title holding an
        // <img onerror> payload must not become live markup when
        // the exported file is opened.
        val chart = app.nota.data.html.ChartSegment(
            payload = "",
            rawHtml = "<div data-type=\"noma-chart\" data-version=\"2\" " +
                "data-chart-type=\"bar\" " +
                "data-title=\"&lt;img src=x onerror=alert(1)&gt;\" data-subtitle=\"\" " +
                "data-categories='[\"Q1\"]' " +
                "data-series='[{\"name\":\"S\",\"values\":[1]}]'></div>",
        )
        val out = NoteExport.noteToHtml("T", listOf(chart))
        assertTrue(
            out.contains(
                "<p class=\"noma-chart-placeholder\">" +
                    "[Bar chart: &lt;img src=x onerror=alert(1)&gt;]</p>",
            ),
        )
        assertFalse(out.contains("<img src=x onerror"))
        // The plain-text export keeps the readable, unescaped title.
        val text = NoteExport.noteToPlainText("T", listOf(chart))
        assertTrue(text.contains("[Bar chart: <img src=x onerror=alert(1)>]"))
    }

    @Test
    fun opaqueHtmlIsScrubbedOfActiveContentOnExport() {
        // The in-note round-trip keeps foreign HTML byte-faithful,
        // but the exported standalone file is opened in a browser:
        // active content must not survive the export path.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                OpaqueSegment(
                    "<div class=\"embed\">" +
                        "<script>alert(1)</script>" +
                        "<p onclick=\"evil()\">Hi</p>" +
                        "<img src=\"https://example.com/x.png\" onerror=\"evil()\">" +
                        "<a href=\"JaVaScRiPt:alert(1)\">click</a>" +
                        "<a href=\"data:text/html;base64,PHNjcmlwdD4=\">data</a>" +
                        "<iframe src=\"https://evil.example\"></iframe>" +
                        "<form action=\"https://evil.example\">" +
                        "<input type=\"text\"></form>" +
                        "<p>Safe &amp; sound</p>" +
                        "</div>",
                ),
            ),
        )
        assertFalse(out.contains("<script"))
        assertFalse(out.contains("alert(1)"))
        assertFalse(out.contains("onclick"))
        assertFalse(out.contains("onerror"))
        assertFalse(out.lowercase().contains("javascript:"))
        assertFalse(out.contains("data:text/html"))
        assertFalse(out.contains("<iframe"))
        assertFalse(out.contains("<form"))
        // Scriptable targets are neutralised, not dropped silently.
        assertTrue(out.contains("<a href=\"#\">click</a>"))
        // Benign markup survives scrubbing unchanged.
        assertTrue(out.contains("<div class=\"embed\">"))
        assertTrue(out.contains("<p>Hi</p>"))
        assertTrue(out.contains("<p>Safe &amp; sound</p>"))
    }

    @Test
    fun benignOpaqueHtmlPassesThroughByteStable() {
        val benign =
            "<table class=\"grid\"><tr><td colspan=\"2\">Cell</td></tr></table>"
        val out = NoteExport.noteToHtml("T", listOf(OpaqueSegment(benign)))
        assertTrue(out.contains(benign))
    }

    @Test
    fun richTextParagraphIsScrubbedOfActiveContentOnExport() {
        // Foreign inline HTML can ride inside an ordinary
        // paragraph, not just an opaque block: the fragment
        // transform is the shared serialisation path, so active
        // content must not survive it either.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TextSegment(
                    "<p>Before <script>alert(1)</script> after</p>" +
                        "<p><b OnClick=\"evil()\">bold</b> " +
                        "<a href=\"javascript:alert(2)\">link</a> " +
                        "<img src=\"https://example.com/x.png\" onerror=\"evil()\"></p>",
                ),
            ),
        )
        assertFalse(out.contains("<script"))
        assertFalse(out.contains("alert(1)"))
        assertFalse(out.contains("alert(2)"))
        assertFalse(out.lowercase().contains("onclick"))
        assertFalse(out.contains("onerror"))
        assertFalse(out.lowercase().contains("javascript:"))
        // Benign survivors keep their shapes.
        assertTrue(out.contains("<p>Before  after</p>"))
        assertTrue(out.contains("<b>bold</b>"))
        assertTrue(out.contains("<a href=\"#\">link</a>"))
        assertTrue(out.contains("<img src=\"https://example.com/x.png\">"))
    }

    @Test
    fun hostileQuoteInlineIsScrubbedOnExport() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                QuoteSegment(
                    "<p><script>alert(1)</script>" +
                        "<em onmouseover=\"evil()\">words</em></p>",
                ),
            ),
        )
        assertFalse(out.contains("<script"))
        assertFalse(out.contains("onmouseover"))
        assertTrue(
            out.contains(
                "<blockquote><p><em>words</em></p></blockquote>",
            ),
        )
    }

    @Test
    fun hostileTaskItemInlineIsScrubbedOnExport() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TaskListSegment(
                    listOf(
                        TaskItem(
                            "Feed <a href=\"data:text/html;base64,AAAA\">the cat</a>" +
                                "<iframe src=\"https://evil.example\"></iframe>",
                            checked = false,
                        ),
                    ),
                ),
            ),
        )
        assertFalse(out.contains("data:text/html"))
        assertFalse(out.contains("<iframe"))
        assertTrue(out.contains("<a href=\"#\">the cat</a>"))
    }

    @Test
    fun hostileTableCellIsScrubbedOnExport() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TableSegment(
                    rows = listOf(
                        listOf(
                            "<b onclick=\"evil()\">cell</b>" +
                                "<script>alert(1)</script>",
                        ),
                    ),
                    headerRow = false,
                ),
            ),
        )
        assertFalse(out.contains("<script"))
        assertFalse(out.contains("onclick"))
        assertTrue(out.contains("<td><b>cell</b></td>"))
    }

    @Test
    fun namespacedUrlAttributeIsScrubbedInFragments() {
        // xlink:href is href to a browser: the scrub must match
        // URL attributes by local name, not by exact spelling.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TextSegment(
                    "<p><svg><a xlink:href=\"javascript:alert(1)\">svg link</a></svg> " +
                        "<a xlink:href=\"https://example.com\">ok</a></p>",
                ),
            ),
        )
        assertFalse(out.lowercase().contains("javascript:"))
        assertTrue(out.contains("<a xlink:href=\"#\">svg link</a>"))
        // A benign namespaced target survives unchanged.
        assertTrue(out.contains("<a xlink:href=\"https://example.com\">ok</a>"))
    }

    @Test
    fun namespacedUrlAttributeIsScrubbedInOpaqueBlocks() {
        // The opaque string scrubber gets the same local-name
        // rule as the fragment scrubber.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                OpaqueSegment(
                    "<svg><a xlink:href=\"javascript:alert(1)\">x</a>" +
                        "<a xlink:href=\"https://example.com\">ok</a></svg>",
                ),
            ),
        )
        assertFalse(out.lowercase().contains("javascript:"))
        assertTrue(out.contains("<a xlink:href=\"#\">x</a>"))
        assertTrue(out.contains("<a xlink:href=\"https://example.com\">ok</a>"))
    }

    @Test
    fun outOfRangeNumericEntitiesBecomeReplacementCharInEveryFormat() {
        // &#x110000; and &#1114112; are past the last code point
        // and &#-1; is negative: Character.toChars throws on all
        // three, which used to abort the whole export in every
        // format. They now decode to U+FFFD (HTML5 behaviour)
        // while valid references still decode normally.
        val segments = listOf(
            TextSegment(
                "<p>hex &#x110000; dec &#1114112; neg &#-1; " +
                    "ok &#233; emoji &#x1F600;</p>",
            ),
        )
        val outputs = listOf(
            NoteExport.noteToHtml("T", segments),
            NoteExport.noteToMarkdown("T", segments),
            NoteExport.noteToPlainText("T", segments),
        )
        for (out in outputs) {
            assertTrue(out.contains("\uFFFD"))
            assertTrue(out.contains("\u00E9"))
            assertTrue(out.contains("\uD83D\uDE00"))
            assertFalse(out.contains("x110000"))
        }
    }

    @Test
    fun benignRichTextExportsByteIdentical() {
        // Regression pin: the scrub must not reshape the benign
        // editor dialect — marks, colour spans with style/class/
        // data-* attributes, links and note-link chips serialise
        // exactly as they did before it existed.
        val paragraph =
            "<p>Rich <strong>bold</strong> " +
                "<span style=\"color:#c0392b\" class=\"hl\" data-x=\"1\">red</span> " +
                "<mark>marked</mark> <u>under</u> <code>a &lt; b</code> " +
                "<a href=\"https://example.com\">site</a> " +
                "<a href=\"mailto:a@example.com\">mail</a> " +
                "<span data-note-link=\"42\">Linked note</span></p>"
        val expected =
            "<p>Rich <strong>bold</strong> " +
                "<span style=\"color:#c0392b\" class=\"hl\" data-x=\"1\">red</span> " +
                "<mark>marked</mark> <u>under</u> <code>a &lt; b</code> " +
                "<a href=\"https://example.com\">site</a> " +
                "<a href=\"mailto:a@example.com\">mail</a> " +
                "Linked note</p>"
        val out = NoteExport.noteToHtml("T", listOf(TextSegment(paragraph)))
        assertTrue(out.contains(expected))
    }

    /**
     * A resolver value built from a crafted attachment mime type
     * (`image/png" onerror="…`, the shape a restored backup can
     * supply unvalidated) must never break out of the exported
     * markup: build() guards the resolver centrally, so the value
     * is treated as unresolved and degrades to the placeholder.
     */
    private val hostileDataResolver: AttachmentResolver = { id ->
        if (id == "img1") {
            "data:image/png\" onerror=\"alert(1);base64,AAAA"
        } else {
            null
        }
    }

    private val pngDataResolver: AttachmentResolver = { id ->
        if (id == "img1") "data:image/png;base64,iVBORw0KGgo=" else null
    }

    @Test
    fun hostileResolvedDataUrlCannotBreakOutOfImageSegment() {
        val out = NoteExport.build(
            "T",
            listOf(ImageSegment(src = "nota-attachment://img1", alt = "Cat")),
            ExportFormat.HTML,
            hostileDataResolver,
        ).content
        assertFalse(out.contains("onerror"))
        assertFalse(out.contains("alert(1)"))
        assertFalse(out.contains("<img"))
        assertTrue(out.contains("<span>[Image]</span>"))
    }

    @Test
    fun hostileResolvedDataUrlCannotBreakOutOfOpaqueImage() {
        val out = NoteExport.build(
            "T",
            listOf(
                OpaqueSegment(
                    "<div><img src=\"nota-attachment://img1\" alt=\"x\"></div>",
                ),
            ),
            ExportFormat.HTML,
            hostileDataResolver,
        ).content
        assertFalse(out.contains("onerror"))
        assertFalse(out.contains("alert(1)"))
        assertTrue(out.contains("<span>[Image]</span>"))
    }

    @Test
    fun legitimateDataUrlStillEmbedsVerbatimThroughBuild() {
        val standalone = NoteExport.build(
            "T",
            listOf(ImageSegment(src = "nota-attachment://img1", alt = "Cat")),
            ExportFormat.HTML,
            pngDataResolver,
        ).content
        assertTrue(
            standalone.contains(
                "<img src=\"data:image/png;base64,iVBORw0KGgo=\" alt=\"Cat\">",
            ),
        )
        val opaque = NoteExport.build(
            "T",
            listOf(
                OpaqueSegment(
                    "<div><img src=\"nota-attachment://img1\"></div>",
                ),
            ),
            ExportFormat.HTML,
            pngDataResolver,
        ).content
        assertTrue(
            opaque.contains(
                "<img src=\"data:image/png;base64,iVBORw0KGgo=\">",
            ),
        )
    }

    @Test
    fun hostileResolvedDataUrlStaysOutOfMarkdownToo() {
        val out = NoteExport.build(
            "T",
            listOf(ImageSegment(src = "nota-attachment://img1", alt = "Cat")),
            ExportFormat.MARKDOWN,
            hostileDataResolver,
        ).content
        assertFalse(out.contains("onerror"))
        assertFalse(out.contains("alert(1)"))
        assertTrue(out.contains("[Image]"))
    }
    @Test
    fun namedEntitySchemeIsScrubbedInOpaqueBlocks() {
        // The opaque scrubber decodes entities before checking a
        // URL's scheme, and browsers know named references the
        // decoder originally didn't: javascript&colon; must be
        // recognised as a scriptable scheme and neutralised.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                OpaqueSegment(
                    "<p><a href=\"javascript&colon;alert(1)\">one</a>" +
                        "<a href=\"java&Tab;script:alert(2)\">two</a></p>",
                ),
            ),
        )
        assertFalse(out.lowercase().contains("javascript"))
        assertFalse(out.contains("&colon;"))
        assertFalse(out.contains("alert("))
        assertTrue(out.contains("<a href=\"#\">one</a>"))
        assertTrue(out.contains("<a href=\"#\">two</a>"))
    }

    @Test
    fun benignColonEntityDecodesInText() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(TextSegment("<p>Time&colon; 5 pm</p>")),
        )
        assertTrue(out.contains("<p>Time: 5 pm</p>"))
    }

    @Test
    fun opaqueAttachmentImagesAreCollectedAndEmbedded() {
        val segments = listOf(
            OpaqueSegment(
                "<picture><img src=\"nota-attachment://img1\" alt=\"Cat\"></picture>",
            ),
        )
        assertEquals(
            listOf("img1"),
            NoteExport.collectAttachmentIds(segments),
        )
        val out = NoteExport.build(
            "T", segments, ExportFormat.HTML, pngDataResolver,
        ).content
        assertTrue(
            out.contains("src=\"data:image/png;base64,iVBORw0KGgo=\""),
        )
        assertFalse(out.contains("[Image]"))
    }

    @Test
    fun svgAnimationElementsAreDroppedFromOpaqueBlocks() {
        // SMIL can animate an anchor's href into a javascript:
        // URL after the attribute scrub has passed — the
        // animation elements themselves must not survive.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                OpaqueSegment(
                    "<svg><a href=\"https://example.com\">" +
                        "<animate attributeName=\"href\" " +
                        "values=\"javascript:alert(1)\"></animate>" +
                        "label</a></svg>",
                ),
            ),
        )
        assertFalse(out.contains("<animate"))
        assertFalse(out.lowercase().contains("javascript:"))
        assertFalse(out.contains("alert(1)"))
        assertTrue(out.contains("label"))
    }

    @Test
    fun svgAnimationElementsAreDroppedFromRichText() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TextSegment(
                    "<p><svg><a href=\"https://example.com\">" +
                        "<set attributeName=\"href\" " +
                        "to=\"javascript:alert(1)\"></set>" +
                        "label</a></svg></p>",
                ),
            ),
        )
        assertFalse(out.contains("<set"))
        assertFalse(out.lowercase().contains("javascript:"))
        assertTrue(out.contains("label"))
    }

    @Test
    fun styleElementsAndCssUrlsDoNotSurviveExport() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TextSegment(
                    html = "<style>body{background:url(https://tracker.example/x)}</style>" +
                        "<p style=\"color:#c0392b;background-image:url(https://tracker.example/y)\">Red</p>",
                ),
            ),
            exportResolver,
        )
        assertFalse(out.contains("tracker.example"))
        assertFalse(out.contains("background-image"))
        assertFalse(out.contains("background:url"))
        // The colour declaration itself is content and stays,
        // in the rebuilt form.
        assertTrue(out.contains("color: #c0392b"))
    }

    @Test
    fun cleanColourStylesPassVerbatimAndBackgroundAttrsDrop() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TextSegment(
                    html = "<p style=\"color:#c0392b\">Red</p>" +
                        "<p background=\"https://tracker.example/bg.png\">Plain</p>",
                ),
            ),
            exportResolver,
        )
        assertTrue(out.contains("style=\"color:#c0392b\""))
        assertFalse(out.contains("tracker.example"))
        assertFalse(out.contains("background="))
    }

    @Test
    fun opaqueStyleMarkupIsScrubbed() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                OpaqueSegment(
                    "<style>.x{background:url(https://tracker.example/z)}</style>" +
                        "<div style=\"background-image:url(https://tracker.example/w);color:#111111\">Kept</div>",
                ),
            ),
            exportResolver,
        )
        assertFalse(out.contains("tracker.example"))
        assertFalse(out.contains(".x{"))
        assertTrue(out.contains("color: #111111"))
        assertTrue(out.contains("Kept"))
    }

    @Test
    fun entitiesRenderAsCharactersInHtmlExport() {
        val out = NoteExport.noteToHtml(
            "T", listOf(TextSegment(html = "<p>&copy; 2026</p>")), exportResolver,
        )
        assertTrue(out.contains("\u00A9 2026"))
        assertFalse(out.contains("&amp;copy;"))
    }

    @Test
    fun activeDataUrlsAreBlockedInLinksButNotImages() {
        // Navigating to an SVG or XHTML data: document runs
        // its scripts; the same URL as an <img> source renders
        // in a script-disabled context and stays.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TextSegment(
                    html = "<p><a href=\"data:image/svg+xml,<svg><script>alert(1)</script></svg>\">svg link</a></p>" +
                        "<p><a href=\"data:application/xhtml+xml,<html/>\">xhtml link</a></p>" +
                        "<p><img src=\"data:image/svg+xml,<svg><rect/></svg>\" alt=\"pic\"></p>",
                ),
            ),
            exportResolver,
        )
        assertTrue(out.contains("href=\"#\""))
        assertFalse(out.contains("alert(1)"))
        assertTrue(out.contains("data:image/svg+xml,"))
        assertFalse(out.contains("href=\"data:"))
    }

    @Test
    fun unquotedOpaqueAttachmentSourcesResolve() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                OpaqueSegment(
                    "<picture><img src=nota-attachment://img1 alt=\"diagram\"></picture>",
                ),
            ),
            pngDataResolver,
        )
        assertTrue(out.contains("src=\"data:image/png;base64,iVBORw0KGgo=\""))
        assertFalse(out.contains("nota-attachment://"))
    }

    @Test
    fun semicolonlessNumericReferencesCannotSmuggleSchemes() {
        // Browsers parse "&#58" (no semicolon) as ':' inside
        // attribute values, so the scheme check must see the
        // decoded form too.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TextSegment(
                    "<p><a href=\"javascript&#58alert(1)\">one</a> " +
                        "<a href=\"&#106avascript:alert(2)\">two</a></p>",
                ),
            ),
        )
        assertFalse(out.lowercase().contains("javascript:"))
        assertFalse(out.contains("javascript&#58"))
        assertFalse(out.contains("&#106avascript"))
        assertFalse(out.contains("alert(1)"))
        assertFalse(out.contains("alert(2)"))
        assertTrue(out.contains("<a href=\"#\">one</a>"))
        assertTrue(out.contains("<a href=\"#\">two</a>"))
    }

    @Test
    fun semicolonlessLegacyReferencesFollowAttributeRules() {
        // In text, "&copy" decodes like a browser shows it.
        // Inside an attribute, "&copy=" must NOT decode —
        // the spec's guard keeps query strings intact.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TextSegment(
                    "<p>Fish &copy chips " +
                        "<a href=\"https://example.com/?a=1&copy=2\">q</a></p>",
                ),
            ),
        )
        assertTrue(out.contains("Fish \u00A9 chips"))
        assertTrue(out.contains("href=\"https://example.com/?a=1&amp;copy=2\""))
    }

    @Test
    fun abruptlyClosedCommentsDoNotHideTagsFromTheScrubber() {
        // "<!-->" and "<!--->" are complete comments to a
        // browser, and "--!>" ends a longer one: markup after
        // them is live, so the scrubber must see the img tag
        // and strip its handler in every form.
        for (payload in listOf(
            "<div><!--><img src=x onerror=alert(1)></div>",
            "<div><!---><img src=x onerror=alert(1)></div>",
            "<div><!-- x --!><img src=x onerror=alert(1)></div>",
        )) {
            val out = NoteExport.noteToHtml(
                "T", listOf(OpaqueSegment(payload)),
            )
            assertTrue(out.contains("<img"))
            assertFalse(out.contains("onerror"))
        }
    }

    @Test
    fun hyphenatedSrcAttributesAreNotMistakenForTheSource() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                OpaqueSegment(
                    "<img data-src=\"nota-attachment://img1\" " +
                        "src=\"nota-attachment://img1\" alt=\"Cat\">",
                ),
            ),
            pngDataResolver,
        )
        // The real src (space-prefixed) now carries the data
        // URL; before the fix the rewrite landed inside
        // data-src and the real src kept its attachment URI.
        assertTrue(out.contains(" src=\"data:image/png;base64,iVBORw0KGgo=\""))
        assertFalse(out.contains(" src=\"nota-attachment://"))
    }


    @Test
    fun entityEscapedAttachmentUrlResolvesInOpaqueBlocks() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                OpaqueSegment(
                    "<img src=\"nota-attachment&#58;//img1\" alt=\"Cat\">",
                ),
            ),
            pngDataResolver,
        )
        assertTrue(out.contains("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(out.contains("nota-attachment"))
    }

    @Test
    fun quotedAngleBracketInAltDoesNotHideTheSource() {
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                OpaqueSegment(
                    "<img alt=\"2 > 1\" src=\"nota-attachment://img1\">",
                ),
            ),
            pngDataResolver,
        )
        assertTrue(out.contains("data:image/png;base64,iVBORw0KGgo="))
        assertFalse(out.contains("nota-attachment"))
    }

    @Test
    fun nestedTasksEmitNestedListInsideParentItem() {
        // Phase 6 (nested checklists): the HTML converter rebuilds
        // the list from the model, so children must be re-emitted
        // as a nested task list inside the parent item's <div> —
        // not dropped, not flattened.
        val out = NoteExport.noteToHtml(
            "T",
            listOf(
                TaskListSegment(
                    listOf(
                        TaskItem(
                            "<p>Parent</p>",
                            checked = false,
                            children = listOf(
                                TaskItem("<p>Child</p>", checked = true),
                            ),
                        ),
                    ),
                ),
            ),
        )
        assertTrue(
            out.contains(
                "<div><p>Parent</p><ul data-type=\"taskList\">" +
                    "<li data-checked=\"true\"><label>" +
                    "<input type=\"checkbox\" checked disabled></label>" +
                    "<div><p>Child</p></div></li></ul></div>",
            ),
        )
    }
}
