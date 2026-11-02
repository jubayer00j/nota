package app.nota.feature.editor

import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Test

/** TEMPORARY probe round 2 — growth/storage dialect questions. Deleted after use. */
class LibraryProbe2Test {

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace(" ", "␣")

    private fun dump(label: String, state: RichTextState): String =
        "$label TEXT=[${esc(state.annotatedString.text)}] " +
            "SEL=${state.selection.start}:${state.selection.end} HTML=[${state.toHtml()}]"

    @Test
    fun probe2() {
        // R1: one-shot multi-newline insert at end (what placeCaretBelowContent does)
        val a = RichTextState()
        a.setHtml("<p>Hello</p>")
        a.addTextAtIndex(a.annotatedString.text.length, "\n\n\n")
        val r1 = dump("R1", a)

        // R2: type into the grown last paragraph
        a.addTextAfterSelection("Deep")
        val r2 = dump("R2", a)

        // R3: save/load cycle of the TYPED-deep HTML — does the spacing survive?
        val b = RichTextState()
        b.setHtml(a.toHtml())
        val r3 = dump("R3", b)

        // R4: save/load cycle of the UNTYPED grown HTML — what phantom spacing survives?
        val c = RichTextState()
        c.setHtml("<p>Hello</p>")
        c.addTextAtIndex(c.annotatedString.text.length, "\n\n\n")
        val grownHtml = c.toHtml()
        val d = RichTextState()
        d.setHtml(grownHtml)
        val r4 = "R4 grownHtml=[$grownHtml] " + dump("reloaded", d)

        // R5: single newline growth + reload (the <br> form)
        val e = RichTextState()
        e.setHtml("<p>Hello</p>")
        e.addTextAtIndex(e.annotatedString.text.length, "\n")
        val oneHtml = e.toHtml()
        val f = RichTextState()
        f.setHtml(oneHtml)
        val r5 = "R5 oneHtml=[$oneHtml] " + dump("reloaded", f)

        // R6: heading-ended growth — style of the new paragraph
        val g = RichTextState()
        g.setHtml("<h1>Title</h1>")
        g.addTextAtIndex(g.annotatedString.text.length, "\n")
        val r6 = dump("R6", g) + " HEADING=${g.currentHeadingStyle}"

        // R7: blank state growth (fresh note)
        val h = RichTextState()
        h.addTextAtIndex(0, "\n\n")
        val r7 = dump("R7", h)

        assertEquals("PROBE2", "$r1 || $r2 || $r3 || $r4 || $r5 || $r6 || $r7")
    }
}
