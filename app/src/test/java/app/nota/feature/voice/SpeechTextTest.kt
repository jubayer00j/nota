package app.nota.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 5.4: the transcript-assembly rules (a port of 1.x
 * speech.ts's pure half) — what a spoken sentence becomes on the
 * page: quick-capture paragraph shape, the voice-note title, and
 * the editor's caret-insertion join.
 */
class SpeechTextTest {

    @Test
    fun appendFinalSegment_trimsAndSkipsEmpty() {
        assertEquals(
            listOf("Hello"),
            SpeechText.appendFinalSegment(emptyList(), "  Hello  "),
        )
        assertEquals(
            listOf("Hello"),
            SpeechText.appendFinalSegment(listOf("Hello"), "   "),
        )
        assertEquals(
            listOf("Hello", "world"),
            SpeechText.appendFinalSegment(listOf("Hello"), "world"),
        )
    }

    @Test
    fun segmentsToParagraphs_oneParagraphPerSegment() {
        assertEquals(
            "First thought.\n\nSecond thought.",
            SpeechText.segmentsToParagraphs(
                listOf(" First thought. ", "", "Second thought."),
            ),
        )
        assertEquals("", SpeechText.segmentsToParagraphs(emptyList()))
    }

    @Test
    fun segmentsToText_joinsWithSingleSpaces() {
        assertEquals(
            "one two three",
            SpeechText.segmentsToText(listOf(" one ", "two", " three ")),
        )
    }

    @Test
    fun voiceNoteTitle_firstSegmentCapped() {
        assertEquals(
            "Buy milk",
            SpeechText.voiceNoteTitle(listOf("", "  Buy milk  ", "and eggs")),
        )
        val long = "a".repeat(250)
        assertEquals(200, SpeechText.voiceNoteTitle(listOf(long)).length)
        assertEquals("", SpeechText.voiceNoteTitle(listOf("  ", "")))
        assertEquals("", SpeechText.voiceNoteTitle(emptyList()))
    }

    @Test
    fun dictationJoin_addsExactlyOneSpaceUnlessPrecededByWhitespace() {
        assertEquals("Hello", SpeechText.dictationJoin("", "Hello"))
        assertEquals("Hello world", SpeechText.dictationJoin("Hello", "world"))
        assertEquals("Hello world", SpeechText.dictationJoin("Hello ", "world"))
        assertEquals("Hello world", SpeechText.dictationJoin("Hello", "  world  "))
        assertEquals("Hello", SpeechText.dictationJoin("Hello", "   "))
    }

    @Test
    fun dictationInsertionText_dependsOnTheCharacterBeforeTheCaret() {
        // Block start / document start: no leading space.
        assertEquals("Hello", SpeechText.dictationInsertionText("", "Hello"))
        // After whitespace: no extra space.
        assertEquals("Hello", SpeechText.dictationInsertionText(" ", "Hello"))
        assertEquals("Hello", SpeechText.dictationInsertionText("\n", "Hello"))
        // Mid-sentence: exactly one space.
        assertEquals(" world", SpeechText.dictationInsertionText("o", "world"))
        // Empty segment inserts nothing.
        assertEquals("", SpeechText.dictationInsertionText("x", "   "))
    }
}
