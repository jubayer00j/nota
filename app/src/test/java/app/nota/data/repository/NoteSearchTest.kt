package app.nota.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Search Quality Pass's pure semantics (v0.27.0) — 1.x's
 * search.ts scoring, snippet windowing and highlight ranges,
 * and semantic.ts's blend, pinned without a database: field
 * weights and their additivity, AND across terms, the snippet
 * window's edges (match at start, deep match, marker-shaped
 * occurrences, no body match), and the blend's boost math and
 * ordering. Pure JVM.
 */
class NoteSearchTest {

    // ---- Terms -------------------------------------------------

    @Test
    fun termsSplitLowercaseAndStripQuotes() {
        assertEquals(listOf("exam", "notes"), NoteSearch.terms("  Exam   NOTES "))
        assertEquals(listOf("exam"), NoteSearch.terms("\"exam\""))
        assertEquals(emptyList<String>(), NoteSearch.terms(""))
        assertEquals(emptyList<String>(), NoteSearch.terms("   "))
    }

    // ---- Scoring (G1) ------------------------------------------

    @Test
    fun fieldWeightsMatch1x() {
        assertEquals(6, NoteSearch.score("Exam tips", "", emptyList(), null, listOf("exam")))
        assertEquals(3, NoteSearch.score("Notes", "the exam", emptyList(), null, listOf("exam")))
        assertEquals(2, NoteSearch.score("Notes", "", listOf("exam"), null, listOf("exam")))
        assertEquals(1, NoteSearch.score("Notes", "", emptyList(), "Exam folder", listOf("exam")))
        // Additive across fields for the same term.
        assertEquals(
            12,
            NoteSearch.score("Exam", "exam", listOf("exam"), "exam", listOf("exam")),
        )
    }

    @Test
    fun scoringIsAndAcrossTermsAndCaseInsensitive() {
        // Both terms in the title: 6 + 6.
        assertEquals(
            12,
            NoteSearch.score("EXAM Tips", "", emptyList(), null, listOf("exam", "tips")),
        )
        // One term nowhere: not a result at all.
        assertNull(NoteSearch.score("Exam", "notes", emptyList(), null, listOf("exam", "zzz")))
        // No terms: not a result.
        assertNull(NoteSearch.score("Exam", "", emptyList(), null, emptyList()))
    }

    @Test
    fun scoringMatchesSubstringsNotJustPrefixes() {
        // 1.x's includes(): a mid-word fragment is a match.
        assertEquals(6, NoteSearch.score("Exam", "", emptyList(), null, listOf("xam")))
        assertEquals(3, NoteSearch.score("Notes", "textbook", emptyList(), null, listOf("ook")))
    }

    // ---- Bengali probe (G9) --------------------------------------
    // Post-quality-pass search is substring scoring over the
    // corpus, exactly like 1.x, so Bengali text must behave like
    // any other text through every pure seam: tokenisation,
    // field weights, mid-word fragments, snippet windowing and
    // highlight ranges — including words built from combining
    // vowel signs, where a code-unit slip would show up as a
    // shifted window or range.

    @Test
    fun bengaliTermsAndFieldWeightsMatchEnglish() {
        assertEquals(listOf("ঢাকা", "ভ্রমণ"), NoteSearch.terms("ঢাকা  ভ্রমণ"))
        assertEquals(6, NoteSearch.score("ঢাকা ভ্রমণ", "", emptyList(), null, listOf("ঢাকা")))
        assertEquals(3, NoteSearch.score("নোট", "আমার শহর ঢাকা", emptyList(), null, listOf("ঢাকা")))
        assertEquals(2, NoteSearch.score("নোট", "", listOf("বাংলা"), null, listOf("বাংলা")))
        assertEquals(1, NoteSearch.score("নোট", "", emptyList(), "ভ্রমণ ডায়েরি", listOf("ভ্রমণ")))
        // AND across terms holds for Bengali too.
        assertNull(
            NoteSearch.score("ঢাকা", "ভ্রমণ কথা", emptyList(), null, listOf("ঢাকা", "চট্টগ্রাম")),
        )
    }

    @Test
    fun bengaliMidWordFragmentScoresAsAMatch() {
        // "লাদে" sits mid-word inside "বাংলাদেশ" — never a
        // token prefix, so only the substring rule finds it.
        assertEquals(
            3,
            NoteSearch.score("নোট", "আমি বাংলাদেশ থেকে এসেছি", emptyList(), null, listOf("লাদে")),
        )
    }

    @Test
    fun bengaliSnippetCentresADeepMatch() {
        val text = "কথা ".repeat(50) + "লক্ষ্য" + " কথা".repeat(50)
        val snippet = NoteSearch.snippet(text, listOf("লক্ষ্য"))
        // The match starts at code unit 200; the window is the
        // same −30/+110 as for English, ellipses on both ends.
        assertEquals("…" + text.substring(170, 310) + "…", snippet)
        assertTrue(snippet.contains("লক্ষ্য"))
    }

    @Test
    fun bengaliHighlightRangesAreExactAroundCombiningSigns() {
        // "বাংলা" is five code units (ব + া + ং + ল + া — two
        // combining signs); the range must cover exactly the
        // rendered word, no drift from the marks around it.
        assertEquals(
            listOf(4..8),
            NoteSearch.highlightRanges("আমি বাংলা বলি", listOf("বাংলা")),
        )
        // "কী" is ক + the combining ী sign: two code units.
        assertEquals(
            listOf(5..6),
            NoteSearch.highlightRanges("তুমি কী করো", listOf("কী")),
        )
    }

    // ---- Snippets (G2) ------------------------------------------

    @Test
    fun snippetWindowsAroundADeepMatch() {
        val text = "x".repeat(100) + " exam " + "y".repeat(200)
        val snippet = NoteSearch.snippet(text, listOf("exam"))
        // Window: 30 chars before the match at index 101, 110
        // from the match start, ellipses on both ends.
        assertEquals("…" + text.substring(71, 211) + "…", snippet)
    }

    @Test
    fun snippetAtTheStartHasNoLeadingEllipsis() {
        val snippet = NoteSearch.snippet("exam results are out", listOf("exam"))
        // 1.x's windowed branch always appends the trailing "…".
        assertEquals("exam results are out…", snippet)
    }

    @Test
    fun snippetFallsBackToTheHeadWhenTheBodyHasNoMatch() {
        val text = "z".repeat(200)
        val snippet = NoteSearch.snippet(text, listOf("exam"))
        assertEquals(text.take(120), snippet)
        assertEquals(120, snippet.length)
    }

    @Test
    fun snippetSkipsGeneratedListMarkers() {
        val text = "1. first\n2. second\nThe answer is 2 eggs"
        val snippet = NoteSearch.snippet(text, listOf("2"))
        // The window centres on the REAL "2" (index 33, the
        // eggs), not the "2." list marker at index 9: it starts
        // 30 chars before the real match and runs to the end.
        assertEquals("…" + text.substring(3, 39) + "…", snippet)
        assertTrue(snippet.contains("2 eggs"))
    }

    @Test
    fun snippetUsesTheMarkerWhenItIsTheOnlyOccurrence() {
        // Typed text can itself be marker-shaped; the fallback is
        // the first occurrence overall, never "no snippet".
        assertEquals("1. one\n2. two…", NoteSearch.snippet("1. one\n2. two", listOf("2")))
    }

    @Test
    fun snippetWithNoTermsIsTheHead() {
        assertEquals("abcdef", NoteSearch.snippet("abcdef", emptyList()))
    }

    @Test
    fun snippetOffsetsSurviveLengthChangingCaseFolds() {
        // U+0130 ("İ") lowercases to TWO code units, so an index
        // taken from a lowercased copy of this body lands 100
        // chars too far right — past the end of the string, in
        // fact: cutting the window from it threw and aborted
        // the search. Offsets come from the original text, so
        // the window lands on the real match.
        val body = "İ".repeat(100) + " exam results"
        val snippet = NoteSearch.snippet(body, listOf("exam"))
        assertTrue(snippet.contains("exam results"))
        assertTrue(snippet.startsWith("…"))
    }

    // ---- Highlight ranges (G2) ----------------------------------

    @Test
    fun highlightRangesCoverEveryOccurrenceCaseInsensitively() {
        assertEquals(
            listOf(0..3, 5..8, 10..13),
            NoteSearch.highlightRanges("Exam exam EXAM", listOf("exam")),
        )
        assertEquals(emptyList<IntRange>(), NoteSearch.highlightRanges("nothing", listOf("exam")))
        assertEquals(emptyList<IntRange>(), NoteSearch.highlightRanges("exam", emptyList()))
    }

    @Test
    fun highlightRangesUseOriginalOffsetsWhenCaseFoldingExpands() {
        // The same U+0130 trap as the snippet: the ranges must
        // index the string the UI renders, not its lowercased
        // expansion (which would put this match at 100..103 in
        // a 54-char string).
        val text = "İ".repeat(50) + "exam"
        assertEquals(listOf(50..53), NoteSearch.highlightRanges(text, listOf("exam")))
    }

    @Test
    fun highlightRangesMergeOverlaps() {
        assertEquals(
            listOf(0..4),
            NoteSearch.highlightRanges("exams", listOf("exam", "exams")),
        )
    }

    // ---- Blend (G5) ----------------------------------------------

    private fun kw(id: String, score: Int, updatedAt: Long) =
        NoteSearch.KeywordScore(id, score, updatedAt)

    @Test
    fun blendWithoutSimilaritiesKeepsKeywordOrderUntouched() {
        val out = NoteSearch.blend(
            keyword = listOf(kw("a", 3, 100), kw("b", 9, 50)),
            similarities = emptyMap(),
            floor = 0.5,
            updatedAtById = emptyMap(),
        )
        assertEquals(listOf("a", "b"), out.map { it.noteId })
        assertEquals(listOf(3.0, 9.0), out.map { it.score })
        assertTrue(out.all { !it.byMeaning && it.similarity == null })
    }

    @Test
    fun blendBoostsKeywordHitsBySimilarityTimesTen() {
        val out = NoteSearch.blend(
            keyword = listOf(kw("a", 6, 100)),
            similarities = mapOf("a" to 0.8),
            floor = 0.5,
            updatedAtById = emptyMap(),
        )
        assertEquals(14.0, out.single().score, 1e-9)
        assertEquals(0.8, out.single().similarity!!, 1e-9)
        assertTrue(!out.single().byMeaning)
    }

    @Test
    fun blendDoesNotBoostBelowTheFloor() {
        val out = NoteSearch.blend(
            keyword = listOf(kw("a", 6, 100)),
            similarities = mapOf("a" to 0.49),
            floor = 0.5,
            updatedAtById = emptyMap(),
        )
        assertEquals(6.0, out.single().score, 1e-9)
    }

    @Test
    fun blendInterleavesMeaningOnlyNotesByScoreThenRecency() {
        val out = NoteSearch.blend(
            keyword = listOf(kw("a", 6, 100), kw("c", 12, 50)),
            similarities = mapOf("a" to 0.8, "b" to 0.9),
            floor = 0.5,
            updatedAtById = mapOf("b" to 200L),
        )
        // a: 6 + 8 = 14; c: 12 untouched; b: 0.9 × 10 = 9.
        assertEquals(listOf("a", "c", "b"), out.map { it.noteId })
        assertEquals(listOf(14.0, 12.0, 9.0), out.map { it.score })
        assertTrue(out.last().byMeaning)
    }

    @Test
    fun blendExcludesMeaningOnlyBelowFloorOrWithoutRecency() {
        val out = NoteSearch.blend(
            keyword = listOf(kw("a", 6, 100)),
            similarities = mapOf("low" to 0.4, "ghost" to 0.9),
            floor = 0.5,
            updatedAtById = mapOf("low" to 300L),
        )
        // "low" is under the floor; "ghost" has no live row
        // (no recency on record) — neither joins.
        assertEquals(listOf("a"), out.map { it.noteId })
    }

    @Test
    fun blendBreaksScoreTiesByRecency() {
        val out = NoteSearch.blend(
            keyword = listOf(kw("d", 5, 300)),
            similarities = mapOf("e" to 0.5),
            floor = 0.5,
            updatedAtById = mapOf("e" to 100L),
        )
        // Both score 5.0; the newer note (d) ranks first.
        assertEquals(listOf("d", "e"), out.map { it.noteId })
    }
}
