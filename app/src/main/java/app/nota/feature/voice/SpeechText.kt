package app.nota.feature.voice

/**
 * Phase 5.4: transcript assembly — a faithful port of the pure half
 * of Noma 1.x's speech.ts. Everything here is deterministic text
 * shaping with no Android in it, so the rules that decide what a
 * spoken sentence becomes on the page are unit-tested directly.
 *
 * Only transcript text ever exists: no engine records or stores
 * audio (1.x's rule, and Nota's).
 */
object SpeechText {

    /** Title cap for a voice note — mirrors the share pipeline's
     *  title cap (1.x VOICE_NOTE_TITLE_MAX_CHARS). */
    const val VOICE_NOTE_TITLE_MAX_CHARS = 200

    /** Append a final segment; whitespace-only segments never land. */
    fun appendFinalSegment(segments: List<String>, text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return segments.toList()
        return segments + trimmed
    }

    /** Quick-capture shape: one paragraph per final segment. */
    fun segmentsToParagraphs(segments: List<String>): String =
        segments
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    /** Quick-capture shape: the segments as flowing text. */
    fun segmentsToText(segments: List<String>): String =
        segments
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    /**
     * A voice note's title: its first spoken segment, trimmed and
     * capped — the same "first line names the note" convention typed
     * notes follow. Empty when nothing was recognised (the caller
     * creates no note then).
     */
    fun voiceNoteTitle(segments: List<String>): String {
        val first = segments.map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
        return first.take(VOICE_NOTE_TITLE_MAX_CHARS).trim()
    }

    /**
     * Editor insertion spacing: joining a dictated segment onto the
     * text before the caret takes exactly one space, and only when
     * the preceding text doesn't already end in whitespace (or is
     * empty).
     */
    fun dictationJoin(before: String, segment: String): String {
        val trimmed = segment.trim()
        if (trimmed.isEmpty()) return before
        if (before.isEmpty() || before.last().isWhitespace()) return before + trimmed
        return "$before $trimmed"
    }

    /**
     * The text to insert at the caret for one final segment, given
     * the single character before the caret ("" at a document/block
     * start — both read as "nothing before", so no leading space).
     * Returns "" when the segment carries no words.
     */
    fun dictationInsertionText(beforeChar: String, segment: String): String {
        val probe = beforeChar.takeLast(1)
        return dictationJoin(probe, segment).drop(probe.length)
    }
}
