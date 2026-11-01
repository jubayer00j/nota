package app.nota.data.text

/**
 * Detects note bodies whose HTML carries markup the Phase 1b plain-text
 * codec cannot represent: block structure (tables, lists, headings,
 * quotes, code blocks, images, dividers) AND inline formatting or link
 * markup (bold/italic/underline/strike, inline code, highlights, coloured
 * spans, and above all <a href>: the codec strips the tag and keeps only
 * the link text, so the URL itself would be lost).
 *
 * Such notes are protected in the plain editor: opening one must never
 * round-trip its body through [PlainTextCodec], or saving would silently
 * flatten the markup (the SPIKE-REPORT's guard condition, applied to the
 * interim editor). Title, folder, tags and state ops stay editable; the
 * body becomes editable again in the Phase 1c rich editor, which owns
 * full HTML fidelity.
 *
 * The plain codec's own output stays non-rich by construction: it emits
 * only <p> and <br> around escaped text, none of the tags below, so
 * genuinely plain notes remain editable in the plain editor.
 */
object RichBody {
    private val richTag = Regex(
        "(?i)<(table|ul|ol|blockquote|pre|hr|img|h[1-6]|div|figure|input)[\\s>/]",
    )
    private val inlineTag = Regex(
        "(?i)<(strong|strike|span|small|code|mark|sub|sup|del|ins|em|b|i|u|s|a)[\\s>/]",
    )
    private val richAttr = Regex("(?i)data-checked|data-nota-block")

    fun isRich(html: String): Boolean =
        html.isNotEmpty() && (richTag.containsMatchIn(html) ||
            inlineTag.containsMatchIn(html) || richAttr.containsMatchIn(html))
}
