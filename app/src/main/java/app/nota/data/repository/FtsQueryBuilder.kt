package app.nota.data.repository

/**
 * Builds an FTS4 MATCH expression from raw user input — the Phase 0 fixed
 * behaviour, extracted as a pure, unit-tested function:
 * split on whitespace, strip `"` from tokens (a raw quote would unbalance
 * the phrase and make SQLite reject MATCH), drop empties, and AND the
 * tokens together as prefix phrases `"token"*`. Null when nothing remains.
 */
object FtsQueryBuilder {
    fun build(raw: String): String? {
        val match = raw.trim().split(Regex("\\s+"))
            .map { it.replace("\"", "") }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "\"$it\"*" }
        return match.ifEmpty { null }
    }
}
