package app.nota.feature.ask

import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AskCitation
import app.nota.data.ai.AskExcerpt
import app.nota.data.ai.SemanticIndexStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ask Nota's pure view logic (Phase 4.3) — the 1.x
 * ask-view.test.ts rules, ported: gate derivation order,
 * the one-turn follow-up context, citation segmentation
 * (only validated numbers become chips), and source cards
 * (one per cited note, first cited chunk, citation order).
 */
class AskThreadTest {

    private fun status(
        indexed: Int,
        eligible: Int,
        canIndex: Boolean = true,
        spaceIsCurrent: Boolean = true,
    ) = SemanticIndexStatus(indexed, eligible, canIndex, spaceIsCurrent)

    private fun excerpt(noteId: String, chunkIndex: Int, title: String, text: String) =
        AskExcerpt(noteId, chunkIndex, title, text, 0.9)

    private fun answerEntry(
        status: AskAnswerStatus,
        text: String = "",
    ): AskEntry.Answer = AskEntry.Answer(
        id = 2,
        status = status,
        text = text,
        citations = emptyList(),
        sentExcerpts = emptyList(),
        disclosure = null,
        errorMessage = null,
    )

    /* ---------------- Gate derivation ---------------- */

    @Test
    fun gatePutsConfigurationBeforeIndexState() {
        assertEquals(AskGate.NO_KEY, deriveAskGate(hasChatKey = false, status = null))
        assertEquals(
            AskGate.NO_KEY,
            deriveAskGate(hasChatKey = false, status = status(3, 3)),
        )
        assertEquals(AskGate.LOADING, deriveAskGate(hasChatKey = true, status = null))
        assertEquals(
            AskGate.NEEDS_EMBEDDING_MODEL,
            deriveAskGate(hasChatKey = true, status = status(0, 0, canIndex = false)),
        )
    }

    @Test
    fun gateReadsTheIndexStates() {
        assertEquals(
            AskGate.NEEDS_INDEX,
            deriveAskGate(hasChatKey = true, status = status(0, 3)),
        )
        assertEquals(
            AskGate.NEEDS_REBUILD,
            deriveAskGate(hasChatKey = true, status = status(2, 3, spaceIsCurrent = false)),
        )
        assertEquals(
            AskGate.READY,
            deriveAskGate(hasChatKey = true, status = status(3, 3)),
        )
        // An empty library has nothing to build: ready, and
        // questions land on the honest no-evidence state.
        assertEquals(
            AskGate.READY,
            deriveAskGate(hasChatKey = true, status = status(0, 0)),
        )
    }

    @Test
    fun staleIsAQuietNoticeNeverAGate() {
        assertTrue(askIndexStale(status(2, 3)))
        assertFalse(askIndexStale(status(3, 3)))
        assertFalse(askIndexStale(status(2, 3, spaceIsCurrent = false)))
        assertFalse(askIndexStale(null))
    }

    /* ---------------- Disclosure ---------------- */

    @Test
    fun disclosureNamesTheNotesAndTheProvider() {
        val one = listOf(excerpt("a", 0, "A", "x"), excerpt("a", 1, "A", "y"))
        assertEquals(
            "Sent excerpts from 1 note to Google Gemini. " +
                "Your index and notes stay on this device.",
            askDisclosureLine(one, askProviderName(AiProviderId.GEMINI)),
        )
        val two = one + excerpt("b", 0, "B", "z")
        assertTrue(
            askDisclosureLine(two, askProviderName(AiProviderId.OPENAI))
                .startsWith("Sent excerpts from 2 notes to OpenAI."),
        )
        assertEquals("your custom endpoint", askProviderName(AiProviderId.CUSTOM))
        assertEquals("this provider", askProviderName(null))
    }

    /* ---------------- Previous turn ---------------- */

    @Test
    fun previousTurnIsOnlyTheLastAnsweredExchange() {
        assertNull(previousTurnOf(emptyList()))
        assertNull(previousTurnOf(listOf(AskEntry.Question(1, "q"))))

        val entries = listOf(
            AskEntry.Question(1, "first?"),
            answerEntry(AskAnswerStatus.ANSWER, "First answer [1]."),
            AskEntry.Question(3, "second?"),
            answerEntry(AskAnswerStatus.ANSWER, "Second answer [1]."),
        )
        assertEquals(
            app.nota.data.ai.AskPreviousTurn("second?", "Second answer [1]."),
            previousTurnOf(entries),
        )
    }

    @Test
    fun previousTurnIgnoresRefusalsNoEvidenceAndErrors() {
        for (status in listOf(
            AskAnswerStatus.REFUSAL,
            AskAnswerStatus.NO_EVIDENCE,
            AskAnswerStatus.ERROR,
        )) {
            val entries = listOf(AskEntry.Question(1, "q?"), answerEntry(status))
            assertNull("status $status must not become context", previousTurnOf(entries))
        }
    }

    /* ---------------- Citation segments ---------------- */

    @Test
    fun segmentsTurnOnlyValidatedNumbersIntoChips() {
        val citations = listOf(AskCitation(1, "a", 0), AskCitation(2, "b", 1))
        val segments = citationSegments("A [1] B [9] C [2].", citations)
        assertEquals(
            listOf(
                AskTextSegment.Text("A "),
                AskTextSegment.Citation(1, "a", 0),
                AskTextSegment.Text(" B [9] C "),
                AskTextSegment.Citation(2, "b", 1),
                AskTextSegment.Text("."),
            ),
            segments,
        )
    }

    @Test
    fun segmentsWithoutCitationsAreOneTextRun() {
        assertEquals(
            listOf(AskTextSegment.Text("plain answer")),
            citationSegments("plain answer", emptyList()),
        )
    }

    /* ---------------- Source cards ---------------- */

    @Test
    fun sourceCardsAreOnePerCitedNoteInCitationOrderFromTheFirstCitedChunk() {
        val sent = listOf(
            excerpt("a", 0, "Alpha", "alpha chunk zero"),
            excerpt("a", 1, "Alpha", "alpha chunk one"),
            excerpt("b", 2, "Beta", "beta chunk two"),
        )
        val citations = listOf(
            AskCitation(1, "a", 1),
            AskCitation(2, "a", 0),
            AskCitation(3, "b", 2),
        )
        assertEquals(
            listOf(
                AskSourceCard("a", 1, "Alpha", "alpha chunk one"),
                AskSourceCard("b", 2, "Beta", "beta chunk two"),
            ),
            sourceCardsFor(sent, citations),
        )
    }

    @Test
    fun sourceCardsDropACitationWhoseExcerptIsMissing() {
        val sent = listOf(excerpt("a", 0, "Alpha", "alpha chunk zero"))
        assertTrue(sourceCardsFor(sent, listOf(AskCitation(1, "a", 5))).isEmpty())
        // …and the drop does not burn the note for a later,
        // valid citation to another of its chunks.
        val citations = listOf(AskCitation(1, "a", 5), AskCitation(2, "a", 0))
        assertEquals(
            listOf(AskSourceCard("a", 0, "Alpha", "alpha chunk zero")),
            sourceCardsFor(sent, citations),
        )
    }
}
