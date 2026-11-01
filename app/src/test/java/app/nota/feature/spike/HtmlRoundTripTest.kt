package app.nota.feature.spike

import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Strict round-trip assertions (spec §5 gate): HTML -> RichTextState -> HTML
 * must preserve these core Noma shapes. If an assertion fails, the failure IS
 * spike evidence — SPIKE-REPORT.md records it and the verdict follows the data.
 */
class HtmlRoundTripTest {

    private fun roundTrip(html: String): String {
        val state = RichTextState()
        state.setHtml(html)
        return state.toHtml()
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()

    @Test
    fun inlineFormattingTextSurvives() {
        val out = roundTrip(SpikeFixtures.INLINE_FORMATTING)
        val text = stripTags(out)
        assertTrue("text lost: $out", text.contains("bold") && text.contains("italic"))
        assertTrue("bold lost: $out", out.contains("<b>") || out.contains("<strong>"))
        assertTrue("italic lost: $out", out.contains("<i>") || out.contains("<em>"))
    }

    @Test
    fun headingsSurviveAsHeadings() {
        val out = roundTrip(SpikeFixtures.HEADINGS)
        assertTrue("h1 lost: $out", out.contains("<h1"))
        assertTrue("h2 lost: $out", out.contains("<h2"))
        assertTrue("h3 lost: $out", out.contains("<h3"))
    }

    @Test
    fun listsSurvive() {
        val out = roundTrip(SpikeFixtures.LISTS)
        assertTrue("ul lost: $out", out.contains("<ul"))
        assertTrue("ol lost: $out", out.contains("<ol"))
        assertTrue("items lost: $out", stripTags(out).contains("Apples"))
    }

    @Test
    fun linkHrefSurvives() {
        val out = roundTrip(SpikeFixtures.LINK_AND_COLOR)
        assertTrue("href lost: $out", out.contains("https://example.com/path"))
    }

    @Test
    fun fullNoteTextSurvives() {
        val out = roundTrip(SpikeFixtures.FULL_NOTE)
        val text = stripTags(out)
        if (!text.contains("আজকের পরিকল্পনা")) fail("Bengali heading text lost: $out")
        if (!text.contains("Physics chapter 4")) fail("task item text lost: $out")
        if (!text.contains("Small steps every day.")) fail("quote text lost: $out")
    }
}
