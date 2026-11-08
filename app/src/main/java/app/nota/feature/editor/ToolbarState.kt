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
     * Phase 6 (Editor Refinement #1, Codex PR #34 P2): true when the
     * selection carries text colour but no SINGLE common colour
     * (mixed). [textColor] is null in that case too, so without this
     * flag the colour menu showed "Default" as active and its clear
     * action — keyed on the common colour — was a no-op. The menu
     * uses the flag to withhold the misleading check and to route
     * the clear through [clearTextColorInSelection].
     */
    val textColorMixed: Boolean,
    /** The highlight twin of [textColorMixed]. */
    val highlightMixed: Boolean,
    /**
     * Phase 6 (Editor Refinement #1, A10): the URL of the link at
     * the caret/selection, or null when the caret is not in a link.
     * The link dialog prefills from this and offers Remove link —
     * before this the dialog opened blank even on an existing link.
     */
    val linkUrl: String?,
)

/**
 * True when a NON-collapsed selection overlaps at least one span
 * whose [field] is specified — how "mixed" is detected: the common
 * style reports nothing while colour is genuinely present.
 */
private fun RichTextState.selectionHasSpanField(
    field: (SpanStyle) -> Boolean,
): Boolean {
    val sel = selection
    if (sel.collapsed) return false
    return annotatedString.spanStyles.any { range ->
        range.start < sel.max && range.end > sel.min && field(range.item)
    }
}

/**
 * Whether a span-field colour counts as COLOUR IN PLAY. Two
 * "empty" representations exist: [Color.Unspecified], and
 * [Color.Transparent] — the library normalises a REMOVED
 * background to Transparent in the annotated string's span list
 * (its common-style view reads Unspecified again, which is why
 * the toolbar's colour value looked clean while a raw span scan
 * kept reporting a survivor). Transparent is never a palette
 * colour and is invisible as either text colour or highlight,
 * so it counts as no colour.
 */
private fun Color.isColourInPlay(): Boolean =
    this != Color.Unspecified && this != Color.Transparent

@OptIn(ExperimentalRichTextApi::class)
internal fun RichTextState.toolbarFormatState(): ToolbarFormatState {
    val span: SpanStyle = currentSpanStyle
    val textColor = span.color.takeIf { it != Color.Unspecified }
    val highlightColor = span.background.takeIf { it != Color.Unspecified }
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
        textColor = textColor,
        highlightColor = highlightColor,
        textColorMixed = textColor == null &&
            selectionHasSpanField { it.color.isColourInPlay() },
        highlightMixed = highlightColor == null &&
            selectionHasSpanField { it.background.isColourInPlay() },
        linkUrl = (currentRichSpanStyle as? RichSpanStyle.Link)?.url,
    )
}

/**
 * Remove text colour from EVERY span in the current selection,
 * mixed colours included, preserving all other formatting
 * (Codex PR #34 P2). A collapsed caret is a no-op: there the
 * common-colour toggle path in the menu already covers clearing,
 * including the staged typing style.
 */
@OptIn(ExperimentalRichTextApi::class)
internal fun RichTextState.clearTextColorInSelection() {
    clearSpanFieldInSelection({ it.color }) { SpanStyle(color = it) }
}

/** The highlight twin of [clearTextColorInSelection]. */
@OptIn(ExperimentalRichTextApi::class)
internal fun RichTextState.clearHighlightInSelection() {
    clearSpanFieldInSelection({ it.background }) { SpanStyle(background = it) }
}

/**
 * Why per-value, and why re-scanning: the library's style engine
 * only honours a removal for a span whose full style EQUALS the
 * requested style on the requested field
 * (handleUpdatingRichSpan's branch), so no single call can clear
 * an unknown colour — a generic "field marker" matches nothing
 * and silently no-ops, and the selection-level removeSpanStyle
 * refuses mixed selections up front. The composition that works
 * is the one the uniform toggle-clear itself uses, minus that
 * gate: range-remove EACH distinct value actually present. A
 * removal also restructures the span tree, so the scan repeats
 * after each pass, bounded, until the selection reports no
 * in-play values of the field. (The scan's notion of "in play"
 * matters as much as the removal: a removed background is
 * normalised to Color.Transparent in the annotated string, which
 * a naive specified-check counts as a survivor forever — see
 * isColourInPlay.)
 */
@OptIn(ExperimentalRichTextApi::class)
private fun RichTextState.clearSpanFieldInSelection(
    field: (SpanStyle) -> Color,
    styleOf: (Color) -> SpanStyle,
) {
    if (selection.collapsed) return
    repeat(4) {
        val values = distinctSpanFieldValues(field)
        if (values.isEmpty()) return
        values.forEach { value -> removeSpanStyle(styleOf(value), selection) }
    }
}

/** The distinct in-play values of one span field across the selection. */
private fun RichTextState.distinctSpanFieldValues(
    field: (SpanStyle) -> Color,
): List<Color> {
    val sel = selection
    return annotatedString.spanStyles
        .filter { range -> range.start < sel.max && range.end > sel.min }
        .map { field(it.item) }
        .filter { it.isColourInPlay() }
        .distinct()
}
