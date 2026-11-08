package app.nota.feature.settings

import app.nota.data.ai.AiProviderId
import app.nota.data.ai.SemanticIndexStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Beginner AI Setup #1: the pure compositions behind the AI
 * card's new behaviour — the honest Test-success line (F5), the
 * Advanced fold's effective-model header (F3), and the Provider
 * card's stale-index one-liner predicate (F9).
 */
class AiSetupCopyTest {

    @Test
    fun testSuccess_presetBlankField_resolvesToDefault_noWarning() {
        // A preset's blank field resolves to its default embedding
        // model (and Save persists the resolved value), so the
        // plain line is the honest one — no warning.
        assertEquals(
            "Connection works — Gemini answered.",
            aiTestSuccessMessage(AiProviderId.GEMINI, ""),
        )
    }

    @Test
    fun testSuccess_customBlankField_addsTheEmbeddingSentence() {
        // Custom has no default embedding model to resolve to, so
        // a blank field really does leave semantic search and Ask
        // without one — the warning is appended, byte-identical.
        assertEquals(
            "Connection works — Custom endpoint answered." +
                " Semantic search and Ask Nota also need an embedding model" +
                " — that field is empty right now (see Advanced — models).",
            aiTestSuccessMessage(AiProviderId.CUSTOM, ""),
        )
    }

    @Test
    fun testSuccess_embeddingSet_isThePlainLineOnly() {
        assertEquals(
            "Connection works — OpenAI answered.",
            aiTestSuccessMessage(AiProviderId.OPENAI, "text-embedding-3-large"),
        )
    }

    @Test
    fun foldHeader_showsBothEffectiveModels() {
        assertEquals(
            "gemini-3.5-flash-lite · gemini-embedding-001",
            advancedModelsSummary("gemini-3.5-flash-lite", "gemini-embedding-001"),
        )
    }

    @Test
    fun foldHeader_blankSidesReadNotSet() {
        assertEquals("not set · not set", advancedModelsSummary("", ""))
        assertEquals(
            "gpt-4o-mini · not set",
            advancedModelsSummary("gpt-4o-mini", ""),
        )
    }

    private fun status(canIndex: Boolean, indexed: Int, current: Boolean) =
        SemanticIndexStatus(
            indexedNotes = indexed,
            eligibleNotes = 5,
            canIndex = canIndex,
            spaceIsCurrent = current,
        )

    @Test
    fun staleNote_showsOnlyForAStaleExistingIndex() {
        assertTrue(indexStaleNoteVisible(status(canIndex = true, indexed = 3, current = false)))
        assertFalse(indexStaleNoteVisible(null))
        assertFalse(indexStaleNoteVisible(status(canIndex = false, indexed = 3, current = false)))
        assertFalse(indexStaleNoteVisible(status(canIndex = true, indexed = 0, current = false)))
        assertFalse(indexStaleNoteVisible(status(canIndex = true, indexed = 3, current = true)))
    }
}
