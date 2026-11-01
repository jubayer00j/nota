package app.nota.feature.spike

import com.mohamedrejeb.richeditor.model.RichTextState
import java.io.File
import org.junit.Test

/**
 * Probe tests: these never assert. Each risky Noma shape is round-tripped and
 * the exact output (or the exception) is written to build/probe-output/ so the
 * SPIKE-REPORT can quote ground truth for shapes whose fidelity is unknown
 * (task lists, tables, highlights, colour spans, quotes, code blocks,
 * dividers, images, undo of structure, trigger feasibility).
 */
class HtmlRoundTripProbeTest {

    private fun probe(name: String, html: String) {
        val dir = File("build/probe-output").apply { mkdirs() }
        val result = try {
            val state = RichTextState()
            state.setHtml(html)
            val out = state.toHtml()
            "INPUT:\n$html\n\nOUTPUT:\n$out\n\nPLAIN TEXT:\n${state.toText()}\n"
        } catch (t: Throwable) {
            "INPUT:\n$html\n\nEXCEPTION:\n${t.stackTraceToString()}\n"
        }
        File(dir, "$name.txt").writeText(result)
    }

    @Test fun probeTaskList() = probe("task-list", SpikeFixtures.TASK_LIST)
    @Test fun probeTable() = probe("table", SpikeFixtures.TABLE)
    @Test fun probeLinkColorHighlight() = probe("link-color-highlight", SpikeFixtures.LINK_AND_COLOR)
    @Test fun probeQuoteAndCode() = probe("quote-code", SpikeFixtures.QUOTE_AND_CODE)
    @Test fun probeDividerAndImage() = probe("divider-image", SpikeFixtures.DIVIDER_AND_IMAGE)
    @Test fun probeInlineFormatting() = probe("inline", SpikeFixtures.INLINE_FORMATTING)
    @Test fun probeHeadings() = probe("headings", SpikeFixtures.HEADINGS)
    @Test fun probeLists() = probe("lists", SpikeFixtures.LISTS)
    @Test fun probeFullNote() = probe("full-note", SpikeFixtures.FULL_NOTE)
}
