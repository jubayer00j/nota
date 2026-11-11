package app.nota.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vector scoring (Phase 4.2): cosine correctness, best-chunk
 * note scoring, the model-keyed floors, and deterministic
 * ordering. Pure JVM.
 */
class SemanticMathTest {

    private fun v(vararg values: Double) = FloatArray(values.size) { values[it].toFloat() }

    @Test
    fun cosineBasics() {
        assertEquals(1.0, SemanticMath.cosine(v(1.0, 0.0), v(1.0, 0.0)), 1e-9)
        assertEquals(0.0, SemanticMath.cosine(v(1.0, 0.0), v(0.0, 1.0)), 1e-9)
        assertEquals(-1.0, SemanticMath.cosine(v(1.0, 0.0), v(-1.0, 0.0)), 1e-9)
        assertEquals(0.96, SemanticMath.cosine(v(3.0, 4.0), v(4.0, 3.0)), 1e-6)
        // Scale-invariance: a vector against its double is 1.
        assertEquals(1.0, SemanticMath.cosine(v(0.3, 0.4), v(0.6, 0.8)), 1e-6)
    }

    @Test
    fun cosineDegenerateInputsAreZeroNotCrash() {
        assertEquals(0.0, SemanticMath.cosine(v(0.0, 0.0), v(1.0, 1.0)), 0.0)
        assertEquals(0.0, SemanticMath.cosine(v(1.0, 2.0), v(1.0)), 0.0)
        assertEquals(0.0, SemanticMath.cosine(FloatArray(0), FloatArray(0)), 0.0)
    }

    @Test
    fun floorsAreKeyedToTheModelNotTheProvider() {
        // Search Quality Pass (v0.27.0): 1.x keys the floor to
        // the embedding MODEL (semantic.ts similarityFloorFor) —
        // only text-embedding-3-large earns 0.38, however it is
        // reached; everything else sits at the 0.5 default. This
        // replaces the provider-keyed table: at each provider's
        // DEFAULT model the behaviour is unchanged (Gemini's
        // default gemini-embedding-001 → 0.5; OpenAI's and
        // OpenRouter's default 3-large → 0.38 — OpenRouter's
        // being vendor-prefixed, which the rule strips).
        assertEquals(0.38, SemanticMath.floorFor("text-embedding-3-large"), 0.0)
        assertEquals(0.38, SemanticMath.floorFor("openai/text-embedding-3-large"), 0.0)
        // Case and stray whitespace never change the verdict.
        assertEquals(0.38, SemanticMath.floorFor(" Text-Embedding-3-Large "), 0.0)
        assertEquals(0.50, SemanticMath.floorFor("text-embedding-3-small"), 0.0)
        assertEquals(0.50, SemanticMath.floorFor("text-embedding-ada-002"), 0.0)
        // Gemini's default embeds at 0.5, as before.
        assertEquals(0.50, SemanticMath.floorFor("gemini-embedding-001"), 0.0)
        assertEquals(0.50, SemanticMath.floorFor("gemini-embedding-2-preview"), 0.0)
        // Unknown / blank models get the conservative default.
        // (The old table gave a Custom endpoint 0.38 sight
        // unseen; a Custom config has no default model at all —
        // semantic search cannot run there until a model is
        // chosen, and then the model's own floor applies.)
        assertEquals(0.50, SemanticMath.floorFor("some-local-model"), 0.0)
        assertEquals(0.50, SemanticMath.floorFor(""), 0.0)
        // And a 3-large reached through any route DOES earn
        // 0.38 now — the one deliberate change of the keying.
        assertEquals(0.38, SemanticMath.floorFor("models/text-embedding-3-large"), 0.0)
    }

    @Test
    fun relatedHitsScoreByBestChunkAndFilterByFloor() {
        val query = v(1.0, 0.0)
        val chunks = mapOf(
            "note-a" to listOf(v(1.0, 0.0)),
            // Best chunk wins: the weak first chunk must not drag
            // the note below its strong second chunk.
            "note-b" to listOf(v(0.0, 1.0), v(0.8, 0.6)),
            "note-c" to listOf(v(0.0, 1.0)), // orthogonal: below any floor
            "note-d" to listOf(v(0.6, 0.8)),
        )
        val hits = SemanticMath.relatedHits(query, chunks, floor = 0.38)
        assertEquals(listOf("note-a", "note-b", "note-d"), hits.map { it.noteId })
        assertEquals(1.0, hits[0].score, 1e-9)
        assertEquals(0.8, hits[1].score, 1e-6)
        assertEquals(0.6, hits[2].score, 1e-6)
    }

    @Test
    fun relatedHitsRespectTheLimit() {
        val query = v(1.0, 0.0)
        val chunks = (1..15).associate { i -> "note-$i" to listOf(v(1.0, 0.0)) }
        val hits = SemanticMath.relatedHits(query, chunks, floor = 0.38, limit = 10)
        assertEquals(10, hits.size)
        assertTrue(hits.all { it.score == 1.0 })
    }
}
