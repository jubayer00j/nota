package app.nota.data.backup

/**
 * A minimal ORDERED JSON writer matching 1.x's
 * `JSON.stringify(value, null, 2)` byte-for-byte for the shapes the
 * backup writer emits (objects, arrays, strings, longs, booleans, null):
 * 2-space indent, `"key": value`, empty array `[]`, empty object `{}`,
 * no trailing newline. org.json is NOT used for writing because its
 * object key order is not the writer's contract.
 */
sealed interface JVal
data class JObj(val fields: List<Pair<String, JVal>>) : JVal
data class JArr(val items: List<JVal>) : JVal
data class JStr(val value: String) : JVal
data class JNum(val value: Long) : JVal
data class JBool(val value: Boolean) : JVal
object JNull : JVal

fun jObj(vararg fields: Pair<String, JVal>): JObj = JObj(fields.toList())
fun jArr(items: List<JVal>): JArr = JArr(items)
fun jStr(value: String?): JVal = if (value == null) JNull else JStr(value)
fun jNum(value: Long): JVal = JNum(value)
fun jNum(value: Int): JVal = JNum(value.toLong())
fun jBool(value: Boolean): JVal = JBool(value)

object JsonPretty {
    fun stringify(value: JVal): String {
        val sb = StringBuilder()
        write(sb, value, 0)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, value: JVal, indent: Int) {
        when (value) {
            is JStr -> sb.append('"').append(escape(value.value)).append('"')
            is JNum -> sb.append(value.value)
            is JBool -> sb.append(if (value.value) "true" else "false")
            JNull -> sb.append("null")
            is app.nota.data.backup.RawJson -> sb.append(value.text)
            is JArr -> {
                if (value.items.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                value.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(",\n")
                    sb.append("  ".repeat(indent + 1))
                    write(sb, item, indent + 1)
                }
                sb.append('\n').append("  ".repeat(indent)).append(']')
            }
            is JObj -> {
                if (value.fields.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                value.fields.forEachIndexed { i, (k, v) ->
                    if (i > 0) sb.append(",\n")
                    sb.append("  ".repeat(indent + 1))
                    sb.append('"').append(escape(k)).append("\": ")
                    write(sb, v, indent + 1)
                }
                sb.append('\n').append("  ".repeat(indent)).append('}')
            }
        }
    }

    /** JSON.stringify string escaping: quotes, backslash and control
     *  characters; everything else (including non-ASCII) passes through. */
    fun escape(value: String): String = buildString(value.length + 8) {
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }
}
