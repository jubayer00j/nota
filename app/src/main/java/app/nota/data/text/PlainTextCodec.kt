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
        s = s.replace(Regex("(?i)</p>"), "\n\n")
        s = s.replace(Regex("(?i)<p[^>]*>"), "")
        s = s.replace(Regex("<[^>]+>"), "")
        s = unescape(s)
        return s.trim().replace(Regex("\n{3,}"), "\n\n")
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
