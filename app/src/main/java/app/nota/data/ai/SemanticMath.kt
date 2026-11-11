package app.nota.data.ai

import kotlin.math.sqrt

/** One note's semantic-search result: its best chunk's cosine
 *  similarity to the query, and which chunk earned it (the
 *  search UI shows that chunk's text as the result's snippet). */
data class SemanticHit(val noteId: String, val score: Double, val bestChunkIndex: Int = -1)

/**
 * Vector scoring for semantic search (Phase 4.2) — pure math, no
 * I/O, so the ranking contract is unit-testable in isolation.
 *
 * Floors: a note counts as related when its BEST chunk clears
 * the similarity floor for the EMBEDDING MODEL in use (Search
 * Quality Pass, v0.27.0 — 1.x's `similarityFloorFor`, replacing
 * the Phase 4.2 per-PROVIDER rule). The floor is calibrated per
 * model on 1.x's measured EN/BN matrix: text-embedding-3-large's
 * true matches land 0.41–0.78 with unrelated controls ≤ 0.36, so
 * it alone earns 0.38; every other model — gemini-embedding-001
 * (true matches 0.71–0.78), text-embedding-3-small (whose
 * cross-language matches score BELOW an unrelated control) and
 * any custom model — keeps the 0.5 default. At both apps'
 * shipped defaults (Gemini → gemini-embedding-001, OpenAI /
 * OpenRouter → text-embedding-3-large) the floors are exactly
 * what the old provider rule produced; the rules diverge only
 * when a non-default model is picked, where the model is what
 * was actually calibrated.
 */
object SemanticMath {

    /**
     * The similarity floor for an embedding model id. Vendor
     * prefixes are stripped first ("openai/text-embedding-3-large"
     * via OpenRouter is the same model as "text-embedding-3-large"
     * via OpenAI), and unknown models get [DEFAULT_FLOOR].
     */
    fun floorFor(embeddingModel: String): Double {
        val bare = embeddingModel.trim().lowercase().substringAfterLast('/')
        return FLOORS[bare] ?: DEFAULT_FLOOR
    }

    /** The floor every model without a calibration entry keeps. */
    const val DEFAULT_FLOOR = 0.50

    private val FLOORS: Map<String, Double> = mapOf(
        "text-embedding-3-large" to 0.38,
    )

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
            var bestIndex = -1
            for ((index, chunk) in chunks.withIndex()) {
                val score = cosine(queryVector, chunk)
                if (score > best) {
                    best = score
                    bestIndex = index
                }
            }
            if (best >= floor) hits += SemanticHit(noteId, best, bestIndex)
        }
        return hits.sortedWith(
            compareByDescending<SemanticHit> { it.score }.thenBy { it.noteId },
        ).take(limit)
    }
}
