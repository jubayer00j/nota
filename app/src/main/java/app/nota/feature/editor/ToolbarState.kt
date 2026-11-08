package app.nota.feature.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.mohamedrejeb.richeditor.annotation.ExperimentalRichTextApi
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.RichSpanStyle
import com.mohamedrejeb.richeditor.model.RichTextState

/**
 * Phase 2.8 (E2) — the toolbar's honest picture of what is active at
 * the caret/selection.
 *
 * Every flag derives from the rich editor state's own live semantics
 * — never from click bookkeeping — so moving the caret into bold
 * text lights Bold without any click, and moving out unlights it:
 *
 * - Inline formats come from [RichTextState.currentSpanStyle]: at a
 *   collapsed caret that is the style of the preceding character
 *   merged with any staged (about-to-type) style; over a selection
 *   it is the style COMMON to every span in the range, so a format
 *   shows active only when it genuinely applies across the whole
 *   selection. (Inside a heading, Bold reads active — a heading's
 *   own default style is bold; that is the library's semantics and
 *   the text really is bold.)
 * - Code and link come from the state's current rich span style.
 * - The heading level is [RichTextState.currentHeadingStyle] — the
 *   paragraph at the selection start, the finest grain the library
 *   exposes for a paragraph-level concept.
 * - List flags are the state's own observable properties; over a
 *   multi-paragraph selection the library only reports a list when
 *   every paragraph in the range is that list type.
 */
internal data class ToolbarFormatState(
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikethrough: Boolean,
    val code: Boolean,
    val link: Boolean,
    val heading: HeadingStyle,
    val unorderedList: Boolean,
    val orderedList: Boolean,
    /** Colour of the current text, or null when it is the default. */
    val textColor: Color?,
    /** Highlight (background) colour, or null when unhighlighted. */
    val highlightColor: Color?,
    /**
     * Phase 6 (Editor Refinement #1, A10): the URL of the link at
     * the caret/selection, or null when the caret is not in a link.
     * The link dialog prefills from this and offers Remove link —
     * before this the dialog opened blank even on an existing link.
     */
    val linkUrl: String?,
)

@OptIn(ExperimentalRichTextApi::class)
internal fun RichTextState.toolbarFormatState(): ToolbarFormatState {
    val span: SpanStyle = currentSpanStyle
    return ToolbarFormatState(
        bold = span.fontWeight == FontWeight.Bold,
        italic = span.fontStyle == FontStyle.Italic,
        underline = span.textDecoration?.contains(TextDecoration.Underline) == true,
        strikethrough = span.textDecoration?.contains(TextDecoration.LineThrough) == true,
        code = isCodeSpan,
        link = isLink,
        heading = currentHeadingStyle,
        unorderedList = isUnorderedList,
        orderedList = isOrderedList,
        textColor = span.color.takeIf { it != Color.Unspecified },
        highlightColor = span.background.takeIf { it != Color.Unspecified },
        linkUrl = (currentRichSpanStyle as? RichSpanStyle.Link)?.url,
    )
}
