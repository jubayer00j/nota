package app.nota.data.ai

/** Prompt input budget (1.x): very long notes are middle-truncated
 *  before they are sent — head + tail kept, elision marked. */
const val AI_MAX_INPUT_CHARS = 24_000

const val AI_MAX_TAGS = 5
const val AI_MAX_TAG_LENGTH = 40

/**
 * Middle-truncation (1.x's truncateMiddle): keeps the head (60%)
 * and tail (40%) of an over-long text so both the opening and the
 * conclusion survive, with an elision marker the model can see.
 */
fun truncateAiInput(text: String, maxChars: Int = AI_MAX_INPUT_CHARS): Pair<String, Boolean> {
    if (text.length <= maxChars) return text to false
    val headLength = maxChars * 6 / 10
    val tailLength = maxChars - headLength
    return (
        text.substring(0, headLength) +
            "\n\n[… middle of the note omitted for length …]\n\n" +
            text.substring(text.length - tailLength)
        ) to true
}

/**
 * The prompts (Phase 4.1) — 1.x's wording, verbatim in spirit:
 * calls happen ONLY inside user-initiated flows (the two editor
 * actions and the Settings Test button); nothing ambient, nothing
 * automatic. Note content sent is the note's plain-text projection,
 * capped by [truncateAiInput].
 */
object AiPrompts {

    /** Settings "Test connection": the tiniest possible call. */
    const val TEST_PROMPT = "Reply with the single word: ready"

    fun summarize(title: String, noteText: String): String = listOf(
        "You are summarizing a personal note for its author.",
        "",
        "Write the summary in exactly this plain-text format:",
        "- First, a summary of the note in 2 to 4 sentences.",
        "- Then, only if the note has clear key points, a blank line followed by up to 3 key points, each on its own line starting with \"- \".",
        "",
        "Plain text only — no markdown headings, no bold, no preamble like \"Here is a summary\".",
        "",
        "Note title: ${title.trim().ifBlank { "(untitled)" }}",
        "",
        "Note:",
        noteText,
    ).joinToString("\n")

    fun suggestTags(
        title: String,
        noteText: String,
        existingTagNames: List<String>,
        currentTagNames: List<String>,
    ): String = listOf(
        "You are suggesting tags for a personal note in a note-taking app.",
        "",
        "Rules:",
        "- Reply with a JSON array of tag name strings only — no other text, no markdown fences.",
        "- Suggest at most $AI_MAX_TAGS tags.",
        "- Prefer tags from the existing library list when they fit. Only invent a new tag when none of the existing ones fit.",
        "- Tags are short: one or two words, lowercase, no \"#\" prefix.",
        "- Do not repeat tags the note already has.",
        "",
        "Existing library tags: ${existingTagNames.joinToString(", ").ifBlank { "(none)" }}",
        "Tags already on this note: ${currentTagNames.joinToString(", ").ifBlank { "(none)" }}",
        "",
        "Note title: ${title.trim().ifBlank { "(untitled)" }}",
        "",
        "Note:",
        noteText,
    ).joinToString("\n")
}
