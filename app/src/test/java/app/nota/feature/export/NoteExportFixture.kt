package app.nota.feature.export

import app.nota.data.html.ChartSegment
import app.nota.data.html.CodeSegment
import app.nota.data.html.DividerSegment
import app.nota.data.html.DocSegment
import app.nota.data.html.FileSegment
import app.nota.data.html.ImageSegment
import app.nota.data.html.OpaqueSegment
import app.nota.data.html.QuoteSegment
import app.nota.data.html.TableSegment
import app.nota.data.html.TaskItem
import app.nota.data.html.TaskListSegment
import app.nota.data.html.TextSegment

/**
 * Shared fixture for the Per-note Export #1 converter suites: one
 * document touching every segment type — headings and inline
 * marks, nested + started lists, task states, a header table with
 * a pipe in a cell, code carrying fence-breaking backticks and
 * tag-shaped text, a quote, resolved / unresolved / external
 * images (standalone and inline), a file block, a divider, a
 * chart, and an opaque block. The title itself needs escaping.
 */

internal const val EXPORT_FIXTURE_TITLE = "Field notes & <draft>"

internal const val EXPORT_DATA_URL = "data:image/jpeg;base64,QUJD"

internal val exportResolver: AttachmentResolver = { id ->
    if (id == "img1") EXPORT_DATA_URL else null
}

internal fun exportFixture(): List<DocSegment> = listOf(
    TextSegment(
        "<h2>Shopping &amp; plans</h2>" +
            "<p>Bring <strong>bold</strong>, <em>italic</em>, <s>struck</s>, " +
            "<code>x &lt; y</code> and a " +
            "<a href=\"https://example.com/list\">link</a>.</p>" +
            "<p>See [[Groceries]] and [[Home|the house list]].</p>",
    ),
    TextSegment(
        "<p><img src=\"nota-attachment://img1\" alt=\"Inline cat\"></p>" +
            "<p><img src=\"nota-attachment://missing\" alt=\"Lost\"></p>",
    ),
    TextSegment(
        "<ul><li>milk</li><li>bread<ul><li>rye</li><li>white</li></ul></li></ul>" +
            "<ol start=\"3\"><li>third</li><li>fourth</li></ol>",
    ),
    TaskListSegment(
        listOf(
            TaskItem("Book <strong>flights</strong>", checked = true),
            TaskItem("Pack", checked = false),
        ),
    ),
    TableSegment(
        rows = listOf(
            listOf("Item", "Qty"),
            listOf("Rice | Basmati", "2"),
        ),
        headerRow = true,
    ),
    CodeSegment("val x = \"a ``` b\" // <not> a tag"),
    QuoteSegment("<p>Measure <em>twice</em>, cut once.</p>"),
    ImageSegment(src = "nota-attachment://img1", alt = "Cat"),
    ImageSegment(src = "nota-attachment://missing", alt = "Gone"),
    ImageSegment(src = "https://example.com/pic.png", alt = "Web [pic]"),
    FileSegment(attachmentId = "file1", fileName = "tickets.pdf"),
    DividerSegment,
    ChartSegment(
        payload = "{\"type\":\"bar\",\"data\":[]}",
        rawHtml = "<div data-nota-chart=\"1\"></div>",
    ),
    OpaqueSegment("<div class=\"embed\"><p>Embedded words</p></div>"),
)
