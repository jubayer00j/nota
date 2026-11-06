package app.nota.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ask Nota's prompt assembly + answer parsing (Phase 4.3) —
 * the 1.x ask.test.ts matrix, ported. Pure JVM: the budgets
 * and the parser are string logic, and every cap here is a
 * hard cap the grounding contract leans on.
 */
class AskNotaTest {

    private fun excerpt(
        noteId: String = "a",
        chunkIndex: Int = 0,
        title: String = "Note A",
        text: String = "some text",
        similarity: Double = 0.9,
    ) = AskExcerpt(noteId, chunkIndex, title, text, similarity)

    /* ---------------- Prompt assembly ---------------- */

    @Test
    fun promptNumbersExcerptsUnderTitlesAndCarriesTheRefusalInstruction() {
        val excerpts = listOf(
            excerpt(noteId = "a", title = "Alpha", text = "alpha text"),
            excerpt(noteId = "b", chunkIndex = 2, title = "Beta", text = "beta text"),
        )
        val built = AskNota.buildAskPrompt("When does it rain?", excerpts)
        assertEquals(excerpts, built.excerpts)
        assertTrue(built.prompt.contains("[1] From \"Alpha\": alpha text"))
        assertTrue(built.prompt.contains("[2] From \"Beta\": beta text"))
        assertTrue(
            built.prompt.contains(
                "reply with exactly this sentence and nothing else: $ASK_REFUSAL_SENTENCE",
            ),
        )
        assertTrue(built.prompt.contains("Answer in the language of the question."))
        assertTrue(built.prompt.contains("always with Western digits (0-9)"))
        assertTrue(built.prompt.endsWith("Question: When does it rain?"))
        assertTrue(built.prompt.length <= AI_MAX_INPUT_CHARS)
    }

    @Test
    fun promptShedsTheLowestRankedChunksWholeToMeetTheExcerptBudget() {
        val big = "x".repeat(7_000)
        val excerpts = listOf(
            excerpt(noteId = "a", title = "First", text = big),
            excerpt(noteId = "b", title = "Second", text = big),
            excerpt(noteId = "c", title = "Third", text = big),
        )
        val built = AskNota.buildAskPrompt("q", excerpts)
        // 3 × 7000 > 16000; 2 × 7000 fits. The third is dropped, not cut.
        assertEquals(listOf("a", "b"), built.excerpts.map { it.noteId })
        assertFalse(built.prompt.contains("From \"Third\""))
        assertTrue(built.prompt.contains("[2] From \"Second\""))
        assertTrue(built.prompt.length <= AI_MAX_INPUT_CHARS)
    }

    @Test
    fun promptMiddleTruncatesALoneOversizedChunkInsteadOfDroppingIt() {
        val built = AskNota.buildAskPrompt("q", listOf(excerpt(text = "y".repeat(20_000))))
        assertEquals(1, built.excerpts.size)
        assertTrue(built.excerpts[0].text.length <= ASK_EXCERPT_BUDGET_CHARS)
        assertTrue(built.excerpts[0].text.contains("middle of the note omitted for length"))
        assertTrue(built.prompt.length <= AI_MAX_INPUT_CHARS)
    }

    @Test
    fun promptHoldsTheTotalCapEvenWhenTheQuestionItselfIsHuge() {
        val built = AskNota.buildAskPrompt(
            "w".repeat(10_000),
            listOf(excerpt(text = "z".repeat(ASK_EXCERPT_BUDGET_CHARS))),
        )
        assertTrue(built.prompt.length <= AI_MAX_INPUT_CHARS)
    }

    @Test
    fun promptBoundsAPathologicalTitleSoTheTotalCapStillHolds() {
        // Codex PR #25 P2: the title rides into the prompt
        // untruncated and uncounted, and the shrink passes can
        // only shrink text and the question — a 30k title
        // defeated the 24k cap outright.
        val built = AskNota.buildAskPrompt(
            "apple?",
            listOf(excerpt(noteId = "n1", title = "T".repeat(30_000), text = "apple text")),
        )
        assertTrue(built.prompt.length <= AI_MAX_INPUT_CHARS)
        assertEquals(1, built.excerpts.size)
        assertTrue(built.excerpts[0].title.length <= ASK_TITLE_BUDGET_CHARS)
        // The citation the model can earn still maps back to
        // the source note + chunk.
        val result = AskNota.parseAskAnswer("Answer [1].", built.excerpts)
        assertTrue(result is AskAnswerResult.Answer)
        assertEquals(
            listOf(AskCitation(1, "n1", 0)),
            (result as AskAnswerResult.Answer).citations,
        )
    }

    @Test
    fun promptIncludesThePreviousTurnLabelledAsConversationWhenBudgetRemains() {
        val built = AskNota.buildAskPrompt(
            "follow-up?",
            listOf(excerpt()),
            AskPreviousTurn("When does it rain?", "In June [1]."),
        )
        assertTrue(built.prompt.contains("Earlier conversation (context only — not evidence):"))
        assertTrue(built.prompt.contains("Q: When does it rain?"))
        assertTrue(built.prompt.contains("A: In June [1]."))
        // The conversation block sits before the evidence, never inside it.
        assertTrue(
            built.prompt.indexOf("Earlier conversation") <
                built.prompt.indexOf("Excerpts from the user's notes:"),
        )
    }

    @Test
    fun promptOmitsThePreviousTurnWhenTheExcerptsLeaveNoBudgetForIt() {
        val excerpts = listOf(
            excerpt(noteId = "a", text = "x".repeat(8_000)),
            excerpt(noteId = "b", text = "x".repeat(8_000)),
        )
        val built = AskNota.buildAskPrompt(
            "q".repeat(7_000),
            excerpts,
            AskPreviousTurn("earlier question", "a".repeat(1_500)),
        )
        assertFalse(built.prompt.contains("Earlier conversation"))
        assertTrue(built.prompt.length <= AI_MAX_INPUT_CHARS)
    }

    @Test
    fun promptTruncatesAnOverLongPreviousTurnToItsBudgetInsteadOfDroppingIt() {
        val built = AskNota.buildAskPrompt(
            "q",
            listOf(excerpt()),
            AskPreviousTurn("earlier", "a".repeat(5_000)),
        )
        assertTrue(built.prompt.contains("Earlier conversation (context only — not evidence):"))
        assertTrue(built.prompt.contains("middle of the note omitted for length"))
        assertTrue(built.prompt.length <= AI_MAX_INPUT_CHARS)
    }

    /* ---------------- Answer parsing ---------------- */

    private val parseExcerpts = listOf(
        excerpt(noteId = "a", chunkIndex = 0),
        excerpt(noteId = "b", chunkIndex = 2),
        excerpt(noteId = "a", chunkIndex = 1),
    )

    @Test
    fun parseMapsCitationsToTheirSourceChunksDeduplicatedInFirstUseOrder() {
        val result = AskNota.parseAskAnswer(
            "Beta says so [2]. Alpha agrees [1], and again [2]. " +
                "Alpha's later chunk adds detail [3].",
            parseExcerpts,
        )
        assertEquals(
            AskAnswerResult.Answer(
                text = "Beta says so [2]. Alpha agrees [1], and again [2]. " +
                    "Alpha's later chunk adds detail [3].",
                citations = listOf(
                    AskCitation(2, "b", 2),
                    AskCitation(1, "a", 0),
                    AskCitation(3, "a", 1),
                ),
            ),
            result,
        )
    }

    @Test
    fun parseStripsHallucinatedCitationNumbersInsteadOfRenderingThem() {
        val result = AskNota.parseAskAnswer(
            "True [1], invented [9], zero [0].",
            listOf(parseExcerpts[0]),
        )
        assertTrue(result is AskAnswerResult.Answer)
        val answer = result as AskAnswerResult.Answer
        assertTrue(answer.text.contains("[1]"))
        assertFalse(answer.text.contains("[9]"))
        assertFalse(answer.text.contains("[0]"))
        assertEquals(listOf(AskCitation(1, "a", 0)), answer.citations)
    }

    @Test
    fun parseDetectsTheRefusalSentenceEvenWrappedInWhitespace() {
        assertEquals(
            AskAnswerResult.Refusal,
            AskNota.parseAskAnswer(ASK_REFUSAL_SENTENCE, parseExcerpts),
        )
        assertEquals(
            AskAnswerResult.Refusal,
            AskNota.parseAskAnswer("\n  $ASK_REFUSAL_SENTENCE\n", parseExcerpts),
        )
    }

    @Test
    fun parseDoesNotMistakeALongerLookalikeSentenceForTheRefusal() {
        val result = AskNota.parseAskAnswer(
            "I couldn't find that in your notes about taxes, but the budget note covers it [1].",
            parseExcerpts,
        )
        assertTrue(result is AskAnswerResult.Answer)
    }

    @Test
    fun parseTreatsAReplyWithNoCitationsAtAllAsARefusal() {
        assertEquals(
            AskAnswerResult.Refusal,
            AskNota.parseAskAnswer("The meeting is on Tuesday at ten.", parseExcerpts),
        )
    }

    @Test
    fun parseTreatsAReplyWhoseOnlyCitationsAreHallucinatedAsARefusal() {
        assertEquals(
            AskAnswerResult.Refusal,
            AskNota.parseAskAnswer("It is in the third note [9], see also [0].", parseExcerpts),
        )
    }

    @Test
    fun parseAcceptsABengaliDigitCitationAndNormalisesTheMarker() {
        val result = AskNota.parseAskAnswer(
            "বাইকের সার্ভিসিং করতে ১২০০ টাকা খরচ হয়েছে [১]।",
            parseExcerpts,
        )
        assertTrue(result is AskAnswerResult.Answer)
        val answer = result as AskAnswerResult.Answer
        assertTrue(answer.text.contains("[1]"))
        assertFalse(answer.text.contains("[১]"))
        // Digits in the prose are untouched.
        assertTrue(answer.text.contains("১২০০"))
        assertEquals(listOf(AskCitation(1, "a", 0)), answer.citations)
    }

    @Test
    fun parseMixesBengaliAndWesternDigitCitationsInFirstUseOrder() {
        val result = AskNota.parseAskAnswer("First [২], then [1], again [২].", parseExcerpts)
        assertEquals(
            AskAnswerResult.Answer(
                text = "First [2], then [1], again [2].",
                citations = listOf(
                    AskCitation(2, "b", 2),
                    AskCitation(1, "a", 0),
                ),
            ),
            result,
        )
    }

    @Test
    fun parseStillRefusesWhenTheOnlyCitationIsAnOutOfRangeBengaliDigit() {
        assertEquals(
            AskAnswerResult.Refusal,
            AskNota.parseAskAnswer("কিছু একটা [৯]।", parseExcerpts),
        )
    }
}
