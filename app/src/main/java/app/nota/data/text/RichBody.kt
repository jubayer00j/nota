package app.nota.data.text

/**
 * Detects note bodies whose HTML carries block structure the Phase 1b
 * plain-text codec cannot represent (tables, lists, headings, quotes,
 * code blocks, images, dividers).
 *
 * Such notes are protected in the plain editor: opening one must never
 * round-trip its body through [PlainTextCodec], or saving would silently
 * flatten the structure (the SPIKE-REPORT's guard condition, applied to
 * the interim editor). Title, folder, tags and state ops stay editable;
 * the body becomes editable again in the Phase 1c rich editor, which owns
 * full HTML fidelity.
 */
object RichBody {
    private val richTag = Regex(
        "(?i)<(table|ul|ol|blockquote|pre|hr|img|h[1-6]|div|figure|input)[\\s>/]",
    )
    private val richAttr = Regex("(?i)data-checked|data-nota-block")

    fun isRich(html: String): Boolean =
        html.isNotEmpty() && (richTag.containsMatchIn(html) || richAttr.containsMatchIn(html))
}
