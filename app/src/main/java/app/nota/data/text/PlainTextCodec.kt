package app.nota.data.text

/**
 * Pragmatic plain-text <-> HTML conversion for PR-B's plain editor.
 *
 * textToHtml: XML-escapes the text, splits paragraphs on blank lines and
 * turns single newlines into <br> inside a <p>.
 * htmlToText: the inverse for bodies this codec (and simple <p>/<br> HTML)
 * produces — <br> and paragraph boundaries become newlines, remaining tags
 * are stripped and entities unescaped. It is NOT a general HTML parser;
 * the rich editor (PR-C) owns full HTML fidelity.
 */
object PlainTextCodec {

    fun textToHtml(text: String): String {
        if (text.isBlank()) return ""
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val paragraphs = normalized.split(Regex("\n[ \t]*\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        return paragraphs.joinToString("") { paragraph ->
            val inner = paragraph.split("\n").joinToString("<br>") { escape(it) }
            "<p>$inner</p>"
        }
    }

    fun htmlToText(html: String): String {
        if (html.isBlank()) return ""
        var s = html
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)<hr\\s*/?>"), "\n\n")
        s = s.replace(Regex("(?i)</p>"), "\n\n")
        // Block boundaries the rich editor produces (headings, quotes, code
        // blocks, list items, table rows) must become line breaks BEFORE the
        // generic tag strip, or adjacent blocks fuse into one word in the
        // plain-text projection ("<h1>Shopping</h1><ul><li>milk</li>" would
        // project as "Shoppingmilk"). Review fix, Phase 1 re-review.
        s = s.replace(Regex("(?i)</(h[1-6]|blockquote|pre|div)>"), "\n\n")
        s = s.replace(Regex("(?i)</li>"), "\n")
        s = s.replace(Regex("(?i)</tr>"), "\n")
        s = s.replace(Regex("(?i)</t[dh]>"), " ")
        s = s.replace(Regex("(?i)<p[^>]*>"), "")
        s = s.replace(Regex("<[^>]+>"), "")
        s = unescape(s)
        return s.trim().replace(Regex("\n{3,}"), "\n\n")
            .replace(Regex("[ \t]+\n"), "\n")
            .replace(Regex("\n[ \t]+"), "\n")
    }

    /**
     * The cell / task-item field SEED projection (Phase 3.8, Codex
     * finding 3 on PR #21): the same structural projection as
     * [htmlToText] — block boundaries become newlines, tags are
     * stripped, entities unescaped — but WITHOUT the final trim and
     * WITHOUT the space-collapses around newlines. A cell whose
     * stored text carries leading/trailing spaces must seed its
     * editor field with those spaces intact: the field's text is
     * the write-back source for the next edit, so a trimming seed
     * silently deletes the user's spaces on that edit. Structural
     * newlines at the edges (produced by a wrapping `<p>`) are
     * still trimmed — they are markup, not text.
     */
    fun htmlToCellText(html: String): String {
        if (html.isEmpty()) return ""
        var s = html
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
        s = s.replace(Regex("(?i)<hr\\s*/?>"), "\n\n")
        s = s.replace(Regex("(?i)</p>"), "\n\n")
        s = s.replace(Regex("(?i)</(h[1-6]|blockquote|pre|div)>"), "\n\n")
        s = s.replace(Regex("(?i)</li>"), "\n")
        s = s.replace(Regex("(?i)</tr>"), "\n")
        s = s.replace(Regex("(?i)</t[dh]>"), " ")
        s = s.replace(Regex("(?i)<p[^>]*>"), "")
        s = s.replace(Regex("<[^>]+>"), "")
        s = unescape(s)
        return s.replace(Regex("\n{3,}"), "\n\n").trim('\n')
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
