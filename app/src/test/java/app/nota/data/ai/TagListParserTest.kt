package app.nota.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tag-list parser edge cases (Phase 4.1): the JSON path (fences,
 * prose around the array, non-string elements), the cleaning
 * rules (dedupe, existing-tag exclusion, caps), and the plain-list
 * fallback for models that ignore the JSON instruction.
 */
class TagListParserTest {

    @Test
    fun cleanJsonArray() {
        assertEquals(
            listOf("work", "travel", "ideas"),
            TagListParser.parse("""["work", "travel", "ideas"]"""),
        )
    }

    @Test
    fun fencedJsonArray() {
        assertEquals(
            listOf("food", "health"),
            TagListParser.parse("```json\n[\"food\", \"health\"]\n```"),
        )
    }

    @Test
    fun proseAroundTheArray() {
        assertEquals(
            listOf("money"),
            TagListParser.parse("""Here are the tags: ["money"] — hope that helps."""),
        )
    }

    @Test
    fun dedupesAndDropsTagsTheNoteAlreadyHas() {
        // "Work"/"work" dedupe to the first form; "#travel" is
        // dropped (the note already has "Travel"); "new" is new.
        assertEquals(
            listOf("Work", "new"),
            TagListParser.parse(
                """["Work", "work", "#travel", "new"]""",
                currentTagNames = listOf("Travel"),
            ),
        )
    }

    @Test
    fun capsAtFiveAndDropsOverlongTags() {
        val longTag = "x".repeat(41)
        assertEquals(
            listOf("a", "b", "c", "d", "e"),
            TagListParser.parse("""["a", "$longTag", "b", "c", "d", "e", "f"]"""),
        )
    }

    @Test
    fun nonStringElementsAreSkipped() {
        assertEquals(
            listOf("real"),
            TagListParser.parse("""[42, true, null, "real", ["nested"]]"""),
        )
    }

    @Test
    fun numberedListFallback() {
        assertEquals(
            listOf("alpha", "beta", "gamma"),
            TagListParser.parse("1. alpha\n2. beta\n3. gamma"),
        )
    }

    @Test
    fun bulletedAndCommaFallbacks() {
        assertEquals(
            listOf("one", "two"),
            TagListParser.parse("- one\n- two"),
        )
        assertEquals(
            listOf("red", "green", "blue"),
            TagListParser.parse("red, green, blue"),
        )
    }

    @Test
    fun garbageYieldsEmpty() {
        assertTrue(TagListParser.parse("").isEmpty())
        assertTrue(TagListParser.parse("[]").isEmpty())
        assertTrue(TagListParser.parse("[ ]").isEmpty())
    }

    @Test
    fun escapedQuotesInsideTagsSurvive() {
        assertEquals(
            listOf("say \"hi\""),
            TagListParser.parse("""["say \"hi\""]"""),
        )
    }

    @Test
    fun unicodeEscapesAreDecoded() {
        // A provider that escapes non-ASCII must yield the real
        // tag, not backslash-dropped debris (Codex review, 4.1).
        assertEquals(
            listOf("café", "naïve"),
            TagListParser.parse("""["caf\u00e9", "na\u00efve"]"""),
        )
    }
}
