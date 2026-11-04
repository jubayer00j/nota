package app.nota.data.ai

import kotlin.math.sqrt

/** One note's semantic-search result: its best chunk's cosine
 *  similarity to the query. */
data class SemanticHit(val noteId: String, val score: Double)

/**
 * Vector scoring for semantic search (Phase 4.2) — pure math, no
 * I/O, so the ranking contract is unit-testable in isolation.
 *
 * Floors: a note joins "Related by meaning" when its BEST chunk
 * clears the provider's similarity floor. The OpenAI-compatible
 * baseline mirrors 1.x's calibration (0.38 admits, 0.5 is a
 * strong match). Gemini's embedding space sits higher — unrelated
 * text routinely scores in the 0.3s–0.4s against
 * gemini-embedding-001 — so Gemini keeps 1.x's shipped 0.5
 * threshold as its floor rather than inheriting 0.38 and
 * flooding the section with noise.
 */
object SemanticMath {

    /** Weakest best-chunk similarity that still counts as related. */
    fun floorFor(provider: AiProviderId): Double = when (provider) {
        AiProviderId.GEMINI -> 0.50
        else -> 0.38
    }

    /** A similarity at or above this is a strong match (the 1.x
     *  SEMANTIC_THRESHOLD on the OpenAI baseline). */
    const val STRONG_SIMILARITY = 0.50

    /** Cosine similarity; 0.0 for mismatched dimensions or a
     *  zero vector (a degenerate provider reply must rank as
     *  "unrelated", never crash the search). */
    fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size || a.isEmpty()) return 0.0
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    /**
     * Scores notes by their best chunk and keeps those clearing
     * [floor], best first. [chunksByNote] maps note id → its
     * stored chunk vectors; ties break by note id so the order
     * is deterministic for tests and stable across calls.
     */
    fun relatedHits(
        queryVector: FloatArray,
        chunksByNote: Map<String, List<FloatArray>>,
        floor: Double,
        limit: Int = 10,
    ): List<SemanticHit> {
        val hits = ArrayList<SemanticHit>()
        for ((noteId, chunks) in chunksByNote) {
            var best = Double.NEGATIVE_INFINITY
            for (chunk in chunks) {
                val score = cosine(queryVector, chunk)
                if (score > best) best = score
            }
            if (best >= floor) hits += SemanticHit(noteId, best)
        }
        return hits.sortedWith(
            compareByDescending<SemanticHit> { it.score }.thenBy { it.noteId },
        ).take(limit)
    }
}
