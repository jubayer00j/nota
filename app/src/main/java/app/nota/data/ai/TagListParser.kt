package app.nota.data.ai

/**
 * Parses a model's tag reply into clean tag names (Phase 4.1).
 *
 * Primary path is 1.x's parseTagSuggestions: strip a markdown
 * fence, take the outermost [...] span, read it as an array of
 * strings. The array read is a small hand-rolled scanner rather
 * than org.json so this parser stays pure Kotlin — testable in
 * fast JVM tests with no Android stubs involved, and with no
 * behaviour difference between device and test runtime.
 *
 * Models don't always honour the JSON-only instruction, so when
 * no usable array is found the parser falls back to reading the
 * reply as a plain list — one candidate per line (numbering /
 * bullets stripped) or comma-separated — which this phase's brief
 * explicitly requires.
 *
 * Either way the cleaning is identical: trim, strip a leading "#",
 * drop blanks and anything over [AI_MAX_TAG_LENGTH], dedupe
 * case-insensitively, drop tags the note already has, cap at
 * [AI_MAX_TAGS].
 */
object TagListParser {

    fun parse(raw: String, currentTagNames: List<String> = emptyList()): List<String> {
        val candidates = jsonCandidates(raw) ?: lineCandidates(raw)
        val current = currentTagNames
            .map { it.trim().trimStart('#').lowercase() }
            .toSet()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<String>()
        for (item in candidates) {
            val clean = item.trim().trimStart('#').trim()
            if (clean.isEmpty() || clean.length > AI_MAX_TAG_LENGTH) continue
            val key = clean.lowercase()
            if (key in seen || key in current) continue
            seen += key
            out += clean
            if (out.size >= AI_MAX_TAGS) break
        }
        return out
    }

    /** The 1.x path: fence-stripped [...] span read as a string
     *  array. Null when there is no array to read (the caller
     *  falls back to line reading); an actually-empty array
     *  stays empty. */
    private fun jsonCandidates(raw: String): List<String>? {
        var text = raw.trim()
        val fence = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
            .find(text)
        if (fence != null) text = fence.groupValues[1].trim()
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return scanStringArray(text.substring(start, end + 1))
    }

    /**
     * Reads the string elements of a flat JSON array literal.
     * Returns null when the span isn't array-shaped at all (no
     * opening bracket / unterminated string), so prose that
     * merely contains brackets still reaches the line fallback.
     * Non-string elements (numbers, booleans, nested values) are
     * skipped, mirroring 1.x's typeof check.
     */
    private fun scanStringArray(span: String): List<String>? {
        val out = mutableListOf<String>()
        var i = 0
        fun skipWs() {
            while (i < span.length && span[i].isWhitespace()) i++
        }
        skipWs()
        if (i >= span.length || span[i] != '[') return null
        i++
        skipWs()
        if (i < span.length && span[i] == ']') return out
        while (i < span.length) {
            skipWs()
            if (i >= span.length) return null
            when (span[i]) {
                '"' -> {
                    i++
                    val sb = StringBuilder()
                    var closed = false
                    while (i < span.length) {
                        val c = span[i]
                        when {
                            c == '\\' && i + 1 < span.length -> {
                                val esc = span[i + 1]
                                val decoded = if (esc == 'u' && i + 5 < span.length) {
                                    span.substring(i + 2, i + 6).toIntOrNull(16)
                                } else {
                                    null
                                }
                                if (decoded != null) {
                                    // \uXXXX — decode it fully;
                                    // dropping the backslash alone
                                    // would corrupt "café" into
                                    // "cafu00e9" (Codex review,
                                    // Phase 4.1).
                                    sb.append(decoded.toChar())
                                    i += 6
                                } else {
                                    sb.append(unescape(esc))
                                    i += 2
                                }
                            }
                            c == '"' -> {
                                closed = true
                                i++
                                break
                            }
                            else -> {
                                sb.append(c)
                                i++
                            }
                        }
                    }
                    if (!closed) return null
                    out += sb.toString()
                }
                ']' -> return out
                ',' -> i++
                else -> {
                    // A non-string element: skip to its end.
                    while (i < span.length && span[i] != ',' && span[i] != ']') i++
                }
            }
        }
        return out
    }

    private fun unescape(c: Char): Char = when (c) {
        'n' -> '\n'
        't' -> '\t'
        'r' -> '\r'
        else -> c
    }

    /** Fallback: one tag per line / comma piece, with list
     *  decoration (numbering, bullets, quotes) stripped. */
    private fun lineCandidates(raw: String): List<String> =
        raw.split('\n', ',')
            .map { piece ->
                piece.trim()
                    .replace(Regex("^\\d+[.)]\\s*"), "")
                    .replace(Regex("^[-*•]\\s*"), "")
                    .trim()
                    .trim('"', '\'')
                    .trim()
            }
            .filter { it.isNotEmpty() }
}
