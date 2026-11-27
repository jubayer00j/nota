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
        // (Codex R47-F1): paragraphs are walked by ranges, never split
        // into a list — a whitespace-heavy body of millions of blank-line
        // separators used to materialise one list entry per paragraph
        // (plus a second split per paragraph for its lines) before the
        // filter ran, exhausting memory before any size check. The
        // separator scan below reproduces Regex("\n[ \t]*\n")
        // leftmost-match semantics exactly: at each '\n', spaces/tabs are
        // skipped and a second '\n' makes the separator; anything else
        // leaves the newline inside the paragraph. Output is identical
        // to the old split-based shape (verified by differential fuzz
        // against it: hand-picked edges, 3,000 random cases, storm
        // cases — 0 mismatches).
        val out = StringBuilder()
        val n = normalized.length
        var i = 0
        while (i < n) {
            var paraEnd = n
            var nextStart = n
            var j = i
            while (true) {
                val p = normalized.indexOf('\n', j)
                if (p < 0) break
                var k = p + 1
                while (k < n && (normalized[k] == ' ' || normalized[k] == '\t')) k++
                if (k < n && normalized[k] == '\n') {
                    paraEnd = p
                    nextStart = k + 1
                    break
                }
                j = p + 1
            }
            // Trim the paragraph exactly as String.trim() does:
            // Char.isWhitespace() is Unicode-aware — it trims a
            // non-breaking space, where the old `<= ' '` predicate
            // did not — so a paragraph of only Unicode whitespace is
            // dropped by the s < e check below, exactly as the old
            // split/trim/filter shape dropped it (Codex R52-F2).
            var s = i
            var e = paraEnd
            while (s < e && normalized[s].isWhitespace()) s++
            while (e > s && normalized[e - 1].isWhitespace()) e--
            if (s < e) {
                out.append("<p>")
                var ls = s
                var firstLine = true
                while (true) {
                    val q = normalized.indexOf('\n', ls)
                    if (q < 0 || q >= e) {
                        if (!firstLine) out.append("<br>")
                        appendEscaped(out, normalized, ls, e)
                        break
                    }
                    if (!firstLine) out.append("<br>")
                    firstLine = false
                    appendEscaped(out, normalized, ls, q)
                    ls = q + 1
                }
                out.append("</p>")
            }
            i = nextStart
        }
        return out.toString()
    }

    /**
     * The old whole-string escape over a range — same mapping
     * (&amp; &lt; &gt; &quot;), applied per character so no per-line
     * substring is ever allocated.
     */
    private fun appendEscaped(out: StringBuilder, text: String, start: Int, end: Int) {
        var i = start
        while (i < end) {
            when (text[i]) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                else -> out.append(text[i])
            }
            i++
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

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}
