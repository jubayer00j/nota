package app.nota.feature.editor

import androidx.compose.ui.text.TextRange
import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** TEMPORARY probe — grounds the library's real text model. Deleted after use. */
class LibraryProbeTest {

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace(" ", "␣")

    private fun dump(label: String, state: RichTextState) {
        fail(
            "$label TEXT=[${esc(state.annotatedString.text)}] " +
                "SEL=${state.selection.start}:${state.selection.end} " +
                "HTML=[${state.toHtml()}]",
        )
    }

    @Test
    fun probe() {
        // P1: two paragraphs — what separator does the annotated text use?
        val two = RichTextState()
        two.setHtml("<p>One</p><p>Two</p>")
        val p1 = "TEXT=[${esc(two.annotatedString.text)}] HTML=[${two.toHtml()}]"

        // P2: single trailing newline via addTextAtIndex
        val a = RichTextState()
        a.setHtml("<p>Hello</p>")
        a.addTextAtIndex(5, "\n")
        val p2 = "TEXT=[${esc(a.annotatedString.text)}] SEL=${a.selection.start} HTML=[${a.toHtml()}]"

        // P3: newline in the middle
        val b = RichTextState()
        b.setHtml("<p>Hello</p>")
        b.addTextAtIndex(2, "\n")
        val p3 = "TEXT=[${esc(b.annotatedString.text)}] SEL=${b.selection.start} HTML=[${b.toHtml()}]"

        // P4: three sequential single newlines at the (moving) end
        val c = RichTextState()
        c.setHtml("<p>Hello</p>")
        repeat(3) { c.addTextAtIndex(c.annotatedString.text.length, "\n") }
        val p4 = "TEXT=[${esc(c.annotatedString.text)}] SEL=${c.selection.start} HTML=[${c.toHtml()}]"

        // P5: growth via setHtml rebuild
        val d = RichTextState()
        d.setHtml("<p>Hello</p>")
        d.setHtml(d.toHtml() + "<p></p>".repeat(3))
        val p5 = "TEXT=[${esc(d.annotatedString.text)}] HTML=[${d.toHtml()}]"

        // P6: paragraph parse with trailing empties from HTML directly
        val e = RichTextState()
        e.setHtml("<p>Hello</p><p></p><p></p>")
        val p6 = "TEXT=[${esc(e.annotatedString.text)}] HTML=[${e.toHtml()}]"

        // P7: caret selection to end after setHtml — where does selection sit?
        val f = RichTextState()
        f.setHtml("<p>One</p><p>Two</p>")
        f.selection = TextRange(f.annotatedString.text.length)
        val p7 = "TEXT=[${esc(f.annotatedString.text)}] SEL=${f.selection.start}"

        assertEquals("PROBE", "P1 $p1 || P2 $p2 || P3 $p3 || P4 $p4 || P5 $p5 || P6 $p6 || P7 $p7")
    }
}
