package app.nota.data.ai

/**
 * Model summary → note HTML for Insert-at-top (Phase 4.1).
 *
 * Model output is UNTRUSTED (1.x's rule): it is parsed as the
 * fixed plain-text format the prompt asks for — prose paragraphs,
 * then "- " key-point lines — and every character is escaped
 * before it becomes HTML. Nothing the model returns can inject
 * markup into a note.
 */
object AiSummaryHtml {

    fun toHtml(summary: String): String {
        val out = StringBuilder()
        val bullets = mutableListOf<String>()
        fun flushBullets() {
            if (bullets.isEmpty()) return
            out.append("<ul>")
            bullets.forEach { out.append("<li>").append(escape(it)).append("</li>") }
            out.append("</ul>")
            bullets.clear()
        }
        summary.lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> flushBullets()
                line.startsWith("- ") || line == "-" -> {
                    val item = line.removePrefix("-").trim()
                    if (item.isNotEmpty()) bullets += item
                }
                else -> {
                    flushBullets()
                    out.append("<p>").append(escape(line)).append("</p>")
                }
            }
        }
        flushBullets()
        return out.toString()
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
