package app.nota.feature.spike

/**
 * HTML fixtures shaped like real Noma 1.x note bodies (Tiptap output), used by
 * the round-trip unit tests. (Phase 6 polish: this object lived in the MAIN
 * source set feeding the Phase 0 spike screen; the screen is deleted and the
 * fixtures — genuine NoteDocument regression data — moved here, to the test
 * source set, so nothing test-only ships in the app.) Noma's editor emits:
 * paragraphs, h1–h3, strong/em/u/s/code spans, `mark` highlights, coloured
 * spans with inline styles, bullet/ordered lists, task lists
 * (`ul[data-type=taskList]` / `li[data-type=taskItem][data-checked]`),
 * blockquotes, `pre>code` blocks, links, tables, `hr`, and inline images.
 */
object SpikeFixtures {

    const val INLINE_FORMATTING =
        "<p>Plain <strong>bold</strong> <em>italic</em> <u>underline</u> " +
            "<s>strike</s> <code>inline code</code> end.</p>"

    const val HEADINGS =
        "<h1>Chapter One</h1><p>Intro paragraph.</p>" +
            "<h2>Section</h2><p>Body text.</p><h3>Subsection</h3><p>More text.</p>"

    const val LISTS =
        "<ul><li>Apples</li><li>Oranges</li></ul>" +
            "<ol><li>First</li><li>Second</li></ol>"

    const val TASK_LIST =
        "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"true\"><div>Done item</div></li>" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div>Open item</div></li></ul>"

    const val LINK_AND_COLOR =
        "<p>See <a href=\"https://example.com/path\">the reference</a> and " +
            "<span style=\"color: #c0392b\">red words</span> plus " +
            "<mark>highlighted words</mark>.</p>"

    const val QUOTE_AND_CODE =
        "<blockquote><p>A quoted line.</p></blockquote>" +
            "<pre><code>fun main() { println(\"hi\") }</code></pre>"

    const val TABLE =
        "<table><tbody><tr><th>Name</th><th>Score</th></tr>" +
            "<tr><td>Physics</td><td>133</td></tr>" +
            "<tr><td>Chemistry</td><td>115</td></tr></tbody></table>"

    const val DIVIDER_AND_IMAGE =
        "<p>Before</p><hr><p>After</p>" +
            "<p><img src=\"attachment://img-1\" alt=\"diagram\"></p>"

    /** One realistic Noma note mixing the everyday shapes. */
    const val FULL_NOTE =
        "<h1>আজকের পরিকল্পনা</h1>" +
            "<p>সকালে <strong>বাজার</strong> যাব, then study.</p>" +
            "<ul data-type=\"taskList\">" +
            "<li data-type=\"taskItem\" data-checked=\"true\"><div>Physics chapter 4</div></li>" +
            "<li data-type=\"taskItem\" data-checked=\"false\"><div>Higher Math — integration</div></li></ul>" +
            "<blockquote><p>Small steps every day.</p></blockquote>" +
            "<p>Notes in [[Backlinks]] style with a <a href=\"https://example.com\">link</a>.</p>"
}
