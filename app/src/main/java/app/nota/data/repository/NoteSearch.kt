package app.nota.data.repository

import app.nota.data.db.NoteListRow

/**
 * One search result as the search screen renders it: the list
 * row, its blended relevance score (1.x's keyword weights, plus
 * the semantic boost once meaning results land), the snippet to
 * show under the title (a match window for keyword hits, the
 * best-matching passage for meaning-only hits, empty for locked
 * notes) and whether the note surfaced by meaning alone.
 */
data class NoteSearchResult(
    val row: NoteListRow,
    val score: Double,
    val snippet: String,
    val byMeaning: Boolean = false,
) {
    /** The note's id — the shape the pre-quality-pass tests and
     *  call sites already read off result lists. */
    val id: String get() = row.id
}

/**
 * Local search semantics (Search Quality Pass, v0.27.0) — a
 * faithful port of Noma 1.x's `search.ts` / `semantic.ts` merge,
 * kept pure so the whole contract is unit-testable off-device:
 *
 * - Terms are the query lowercased and split on whitespace (the
 *   same tokenisation [FtsQueryBuilder] feeds FTS, quotes
 *   stripped); a note must match EVERY term somewhere (AND).
 * - A "match" is a raw substring test, exactly like 1.x's
 *   `includes()` — mid-word fragments count. FTS remains the
 *   fast prefix pass that proposes candidates; this layer is
 *   what scores them and what the substring fallback runs.
 * - Per term, fields score additively: title +6, body +3,
 *   tag names +2, folder name +1. Results sort by score, then
 *   by recency — 1.x's order.
 * - Locked notes are scored by the caller with an EMPTY body
 *   (their sealed text is never a haystack) and get no snippet;
 *   title, tag and folder names stay searchable, as in 1.x.
 */
object NoteSearch {

    const val WEIGHT_TITLE = 6
    const val WEIGHT_BODY = 3
    const val WEIGHT_TAGS = 2
    const val WEIGHT_FOLDER = 1

    /** 1.x's SEMANTIC_WEIGHT: similarity 0.8 ≈ a title hit. */
    const val SEMANTIC_WEIGHT = 10.0

    /** Terms of a raw query, in the order typed. */
    fun terms(raw: String): List<String> =
        raw.trim().lowercase().split(Regex("\\s+"))
            .map { it.replace("\"", "") }
            .filter { it.isNotEmpty() }

    /**
     * 1.x's score for one note: the sum over terms of the field
     * weights each term hits, or null when any term hits nothing
     * (the note is not a result). [body] must already honour the
     * lock discipline — a locked note's body arrives as "".
     */
    fun score(
        title: String,
        body: String,
        tagNames: List<String>,
        folderName: String?,
        terms: List<String>,
    ): Int? {
        if (terms.isEmpty()) return null
        val titleLower = title.lowercase()
        val bodyLower = body.lowercase()
        val tagsJoined = tagNames.joinToString(" ") { it.lowercase() }
        val folderLower = folderName?.lowercase() ?: ""
        var total = 0
        for (term in terms) {
            var termScore = 0
            if (titleLower.contains(term)) termScore += WEIGHT_TITLE
            if (bodyLower.contains(term)) termScore += WEIGHT_BODY
            if (tagsJoined.contains(term)) termScore += WEIGHT_TAGS
            if (folderLower.contains(term)) termScore += WEIGHT_FOLDER
            if (termScore == 0) return null
            total += termScore
        }
        return total
    }

    /**
     * 1.x's snippet for a matched note, cut from the note's plain
     * text: when the first term occurs in the body, a window of
     * −30/+110 chars around its first REAL occurrence (generated
     * list markers are skipped — see [findSnippetMatch]), with a
     * leading "…" when the window starts mid-note and a trailing
     * "…" always (1.x's exact shape); otherwise the head of the
     * note (a title/tag/folder-only match), first 120 chars.
     */
    fun snippet(plainText: String, terms: List<String>): String {
        val firstTerm = terms.firstOrNull() ?: return plainText.take(HEAD_CHARS)
        val lower = plainText.lowercase()
        if (lower.indexOf(firstTerm) >= 0) {
            val idx = findSnippetMatch(lower, firstTerm)
            if (idx >= 0) {
                val prefix = if (idx > WINDOW_BEFORE) "…" else ""
                val end = minOf(plainText.length, idx + WINDOW_AFTER)
                return prefix + plainText.substring(maxOf(0, idx - WINDOW_BEFORE), end) + "…"
            }
        }
        return plainText.take(HEAD_CHARS)
    }

    /**
     * Where to window the snippet for a body match: the first
     * occurrence of [term] in the lowercased text that is real
     * note content, skipping occurrences that are generated list
     * markers — a numeric query would otherwise window on an
     * item's printed number instead of the match the scoring
     * actually found. When every occurrence is marker-shaped
     * (typed text can itself look like "2. …"), fall back to the
     * first occurrence overall; -1 when the term doesn't occur.
     * Verbatim port of 1.x's findSnippetMatch.
     */
    private fun findSnippetMatch(lowerText: String, term: String): Int {
        var firstIdx = -1
        var from = 0
        while (true) {
            val idx = lowerText.indexOf(term, from)
            if (idx < 0) return firstIdx
            if (firstIdx < 0) firstIdx = idx
            val end = markerTokenEnd(lowerText, idx, term)
            if (end < 0) return idx
            from = end
        }
    }

    /**
     * The index just past the token when the occurrence of [term]
     * at [idx] is part of a generated ordered-list marker, or -1
     * when it isn't. An occurrence only counts as marker-shaped
     * when it starts at a token boundary (start of text, or a
     * non-alphanumeric char before it), the term sits inside that
     * maximal alphanumeric token, and the token is immediately
     * followed by "." and a space (or "." at the end of the
     * text). Verbatim port of 1.x's markerTokenEnd.
     */
    private fun markerTokenEnd(lowerText: String, idx: Int, term: String): Int {
        fun isAlnum(c: Char) = c in 'a'..'z' || c in '0'..'9'
        if (idx > 0 && isAlnum(lowerText[idx - 1])) return -1
        var end = idx
        while (end < lowerText.length && isAlnum(lowerText[end])) end += 1
        if (end - idx < term.length) return -1
        if (end >= lowerText.length || lowerText[end] != '.') return -1
        val after: Char? = if (end + 1 < lowerText.length) lowerText[end + 1] else null
        return if (after == null || after == ' ') end else -1
    }

    /**
     * Every occurrence range of any term in [text], matched
     * case-insensitively, sorted and merged where they overlap
     * or touch — the ranges the search UI highlights (1.x's
     * highlightTerms, expressed as spans instead of parts).
     */
    fun highlightRanges(text: String, terms: List<String>): List<IntRange> {
        if (text.isEmpty() || terms.isEmpty()) return emptyList()
        val lower = text.lowercase()
        val found = ArrayList<IntRange>()
        for (term in terms) {
            if (term.isEmpty()) continue
            var from = 0
            while (true) {
                val idx = lower.indexOf(term, from)
                if (idx < 0) break
                found += idx until idx + term.length
                from = idx + 1
            }
        }
        if (found.isEmpty()) return emptyList()
        found.sortBy { it.first }
        val merged = ArrayList<IntRange>(found.size)
        for (range in found) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                merged += range
            }
        }
        return merged
    }

    /** A keyword result entering the blend: its 1.x score and
     *  recency (the tie-break). */
    data class KeywordScore(val noteId: String, val score: Int, val updatedAt: Long)

    /** A blended result: final score, whether meaning alone
     *  surfaced the note, and the similarity that contributed
     *  (null when no semantic signal touched this row). */
    data class BlendedScore(
        val noteId: String,
        val score: Double,
        val byMeaning: Boolean,
        val similarity: Double?,
    )

    /**
     * 1.x's mergeSemanticResults: a keyword hit whose similarity
     * clears [floor] gains similarity × [SEMANTIC_WEIGHT];
     * semantic-only notes above the floor join with that same
     * product as their score; the whole list re-sorts by score,
     * then recency. With no similarities at all, the keyword
     * results come back untouched (their incoming order kept).
     * [updatedAtById] supplies recency for semantic-only notes —
     * one without a known recency (no live row) never joins.
     */
    fun blend(
        keyword: List<KeywordScore>,
        similarities: Map<String, Double>,
        floor: Double,
        updatedAtById: Map<String, Long>,
    ): List<BlendedScore> {
        if (similarities.isEmpty()) {
            return keyword.map { BlendedScore(it.noteId, it.score.toDouble(), false, null) }
        }
        val seen = HashSet<String>()
        val merged = ArrayList<BlendedScore>(keyword.size + similarities.size)
        for (hit in keyword) {
            seen += hit.noteId
            val similarity = similarities[hit.noteId]
            if (similarity != null && similarity >= floor) {
                merged += BlendedScore(
                    hit.noteId, hit.score + similarity * SEMANTIC_WEIGHT, false, similarity,
                )
            } else {
                merged += BlendedScore(hit.noteId, hit.score.toDouble(), false, similarity)
            }
        }
        for ((noteId, similarity) in similarities) {
            if (noteId in seen || similarity < floor) continue
            val updatedAt = updatedAtById[noteId] ?: continue
            merged += BlendedScore(noteId, similarity * SEMANTIC_WEIGHT, true, similarity)
        }
        val recency = HashMap<String, Long>()
        for (hit in keyword) recency[hit.noteId] = hit.updatedAt
        recency.putAll(updatedAtById)
        return merged.sortedWith(
            compareByDescending<BlendedScore> { it.score }
                .thenByDescending { recency[it.noteId] ?: Long.MIN_VALUE }
                .thenBy { it.noteId },
        )
    }

    private const val WINDOW_BEFORE = 30
    private const val WINDOW_AFTER = 110
    private const val HEAD_CHARS = 120
}
