package app.nota.data.chart

/**
 * A tiny strict JSON reader (Charts PR-A) producing a plain value
 * tree — the `JSON.parse` half of 1.x's `safeJsonParse`, ported.
 *
 * Hand-rolled rather than org.json for the reason [app.nota.data.ai.TagListParser]
 * documents: chart parsing must behave identically in fast JVM
 * tests and on device, with no Android stubs involved. The grammar
 * is strict JSON exactly as `JSON.parse` accepts it (no trailing
 * commas, no single quotes, no unquoted keys, no trailing content);
 * any deviation returns null, which callers treat the way 1.x
 * treats a `safeJsonParse` failure — substitute the fallback.
 *
 * Numbers become [Double]s, overflowing to ±Infinity on huge
 * exponents just like `JSON.parse` (`1e999` → Infinity); the chart
 * normaliser's finite checks then apply, again like 1.x.
 */
sealed interface JsonValue

data class JsonObject(val entries: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = entries[key]
}

data class JsonArray(val items: List<JsonValue>) : JsonValue

data class JsonString(val value: String) : JsonValue

data class JsonNumber(val value: Double) : JsonValue

data class JsonBool(val value: Boolean) : JsonValue

data object JsonNull : JsonValue

object MiniJson {

    /**
     * Nesting bound: real chart JSON nests about five levels.
     * Beyond this the document is treated as a parse failure (the
     * caller substitutes its fallback) instead of recursing until
     * the stack gives out on a crafted payload.
     */
    private const val MAX_DEPTH = 64

    /** Parses [raw] as one complete JSON document; null on any syntax error. */
    fun parse(raw: String): JsonValue? {
        val parser = Parser(raw)
        parser.skipWhitespace()
        val value = parser.readValue(0) ?: return null
        parser.skipWhitespace()
        return if (parser.atEnd()) value else null
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun readValue(depth: Int): JsonValue? {
            if (depth > MAX_DEPTH) return null
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()?.let { JsonString(it) }
                't' -> readLiteral("true", JsonBool(true))
                'f' -> readLiteral("false", JsonBool(false))
                'n' -> readLiteral("null", JsonNull)
                '-', in '0'..'9' -> readNumber()
                else -> null
            }
        }

        private fun readLiteral(word: String, value: JsonValue): JsonValue? {
            if (!s.startsWith(word, i)) return null
            i += word.length
            return value
        }

        private fun readObject(depth: Int): JsonObject? {
            i++ // consume '{'
            val entries = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (i < s.length && s[i] == '}') {
                i++
                return JsonObject(entries)
            }
            while (true) {
                skipWhitespace()
                if (i >= s.length || s[i] != '"') return null
                val key = readString() ?: return null
                skipWhitespace()
                if (i >= s.length || s[i] != ':') return null
                i++
                skipWhitespace()
                val value = readValue(depth + 1) ?: return null
                entries[key] = value // duplicate keys: last wins, as JSON.parse
                skipWhitespace()
                if (i >= s.length) return null
                when (s[i]) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return JsonObject(entries)
                    }
                    else -> return null
                }
            }
        }

        private fun readArray(depth: Int): JsonArray? {
            i++ // consume '['
            val items = mutableListOf<JsonValue>()
            skipWhitespace()
            if (i < s.length && s[i] == ']') {
                i++
                return JsonArray(items)
            }
            while (true) {
                skipWhitespace()
                val value = readValue(depth + 1) ?: return null
                items += value
                skipWhitespace()
                if (i >= s.length) return null
                when (s[i]) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return JsonArray(items)
                    }
                    else -> return null
                }
            }
        }

        /** Reads a string starting at the opening quote; null on bad syntax. */
        private fun readString(): String? {
            i++ // consume '"'
            val out = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '"' -> {
                        i++
                        return out.toString()
                    }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) return null
                        when (s[i]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) return null
                                val hex = s.substring(i + 1, i + 5)
                                val code = hex.toIntOrNull(16) ?: return null
                                // Appending the raw UTF-16 unit reproduces
                                // JS string semantics, surrogate pairs included.
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> return null
                        }
                        i++
                    }
                    c < ' ' -> return null // unescaped control character
                    else -> {
                        out.append(c)
                        i++
                    }
                }
            }
            return null
        }

        // JSON digits are ASCII only. (Char.isDigit() also accepts
        // e.g. Arabic-Indic digits, which String.toDouble then
        // rejects by THROWING — a crafted payload must degrade to a
        // parse failure here, never crash the caller.)
        private fun readNumber(): JsonNumber? {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) return null
            when {
                s[i] == '0' -> i++
                s[i] in '1'..'9' -> while (i < s.length && s[i] in '0'..'9') i++
                else -> return null
            }
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || s[i] !in '0'..'9') return null
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') return null
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(start, i)
            // Grammar above guarantees a valid literal; toDouble
            // overflows to ±Infinity exactly like JSON.parse.
            return JsonNumber(text.toDouble())
        }
    }
}
