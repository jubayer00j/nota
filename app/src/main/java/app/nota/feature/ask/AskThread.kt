package app.nota.feature.ask

import app.nota.data.ai.AiProviderId
import app.nota.data.ai.AskCitation
import app.nota.data.ai.AskExcerpt
import app.nota.data.ai.AskPreviousTurn
import app.nota.data.ai.SemanticIndexStatus

/**
 * Pure view logic for the Ask Nota screen (Phase 4.3) — the
 * port of 1.x's ask-view.ts: which gate state the screen is
 * in, the in-memory thread's shape, and how a parsed answer
 * maps to citation chips and source cards. Kept free of
 * Compose so every rule is unit-testable; [AskViewModel] and
 * [AskScreen] only wire these to the retrieval/generation
 * seams.
 */

/**
 * The gate shown before (or instead of) the thread. Order
 * matters — the derivation checks configuration before index
 * state, so a user without a key never sees index copy.
 * Nota's shape is smaller than 1.x's: every provider here can
 * embed (there is no embeddings-less preset), so the only
 * configuration gap is a Custom slot whose embedding model
 * was never filled in.
 */
enum class AskGate {
    /** Index status hasn't loaded yet. */
    LOADING,

    /** No saved key for the active provider — BYOK explainer
     *  plus a route to Settings. */
    NO_KEY,

    /** A key is saved but no embedding model resolves (a
     *  Custom slot with the field blank). */
    NEEDS_EMBEDDING_MODEL,

    /** Eligible notes exist but none are indexed yet — Build. */
    NEEDS_INDEX,

    /** The stored vectors belong to another provider/model
     *  space — Rebuild. */
    NEEDS_REBUILD,

    /** The thread is usable. A stale notice may still ride
     *  on top of it (see [askIndexStale]). */
    READY,
}

fun deriveAskGate(hasChatKey: Boolean, status: SemanticIndexStatus?): AskGate = when {
    !hasChatKey -> AskGate.NO_KEY
    status == null -> AskGate.LOADING
    !status.canIndex -> AskGate.NEEDS_EMBEDDING_MODEL
    status.indexedNotes == 0 && status.eligibleNotes > 0 -> AskGate.NEEDS_INDEX
    status.indexedNotes > 0 && !status.spaceIsCurrent -> AskGate.NEEDS_REBUILD
    // An empty library also has zero indexed notes, but nothing
    // is waiting to be built there, so it falls through to
    // READY and every question honestly lands on the
    // no-evidence state (1.x's reasoning, same conclusion).
    else -> AskGate.READY
}

/** True when a ready index is behind the library — a quiet
 *  notice, never a gate. */
fun askIndexStale(status: SemanticIndexStatus?): Boolean =
    status != null && status.spaceIsCurrent && status.indexedNotes < status.eligibleNotes

/**
 * The provider's display name for the disclosure line,
 * mirroring 1.x's naming (Google Gemini / preset label / your
 * custom endpoint) so Ask and Settings never disagree about
 * who received text.
 */
fun askProviderName(provider: AiProviderId?): String = when (provider) {
    AiProviderId.GEMINI -> "Google Gemini"
    AiProviderId.CUSTOM -> "your custom endpoint"
    AiProviderId.OPENAI -> "OpenAI"
    AiProviderId.OPENROUTER -> "OpenRouter"
    null -> "this provider"
}

/** The disclosure sentence, worded for the excerpts actually
 *  sent (1.x §4 wording). */
fun askDisclosureLine(sentExcerpts: List<AskExcerpt>, providerName: String): String {
    val notes = sentExcerpts.map { it.noteId }.toSet().size
    return "Sent excerpts from $notes note${if (notes == 1) "" else "s"} " +
        "to $providerName. Your index and notes stay on this device."
}

// ---------------------------------------------------------------------------
// Thread state — memory only (1.x: no Room table, no prefs).
// ---------------------------------------------------------------------------

enum class AskAnswerStatus { ANSWER, REFUSAL, NO_EVIDENCE, ERROR }

sealed interface AskEntry {
    val id: Int

    data class Question(
        override val id: Int,
        val text: String,
    ) : AskEntry

    data class Answer(
        override val id: Int,
        val status: AskAnswerStatus,
        /** Parsed answer text — only for status ANSWER. */
        val text: String,
        val citations: List<AskCitation>,
        /**
         * The excerpts actually sent to the provider
         * (post-budget), so source cards and the disclosure
         * describe the real prompt, not the wider retrieval.
         * Empty when no provider call happened.
         */
        val sentExcerpts: List<AskExcerpt>,
        /** The disclosure line for this exchange, or null when
         *  nothing was sent. */
        val disclosure: String?,
        /** Calm AiError copy — only for status ERROR. */
        val errorMessage: String?,
    ) : AskEntry
}

/**
 * The follow-up context for the next question: only the
 * immediately previous exchange, and only when it actually
 * produced an answer — a refusal, a no-evidence result or an
 * error is not conversation the model should build on, and
 * anything older than one turn is out of scope (1.x §3).
 */
fun previousTurnOf(entries: List<AskEntry>): AskPreviousTurn? {
    val answer = entries.lastOrNull() as? AskEntry.Answer ?: return null
    val question = entries.getOrNull(entries.size - 2) as? AskEntry.Question ?: return null
    if (answer.status != AskAnswerStatus.ANSWER) return null
    return AskPreviousTurn(question.text, answer.text)
}

// ---------------------------------------------------------------------------
// Answer rendering: citation chips and source cards.
// ---------------------------------------------------------------------------

sealed interface AskTextSegment {
    data class Text(val text: String) : AskTextSegment
    data class Citation(val n: Int, val noteId: String, val chunkIndex: Int) : AskTextSegment
}

/**
 * Splits answer text into plain runs and citation chips.
 * Only numbers the parser validated (they arrive in
 * [citations]) become chips — anything else that merely
 * looks like [n] stays literal text, so a chip can never
 * point at a source the answer didn't earn.
 */
fun citationSegments(text: String, citations: List<AskCitation>): List<AskTextSegment> {
    val byNumber = citations.associateBy { it.n }
    val segments = mutableListOf<AskTextSegment>()
    val pattern = Regex("\\[(\\d{1,3})\\]")
    var last = 0
    for (match in pattern.findAll(text)) {
        val citation = byNumber[match.groupValues[1].toInt()] ?: continue
        if (match.range.first > last) {
            segments += AskTextSegment.Text(text.substring(last, match.range.first))
        }
        segments += AskTextSegment.Citation(citation.n, citation.noteId, citation.chunkIndex)
        last = match.range.last + 1
    }
    if (last < text.length) segments += AskTextSegment.Text(text.substring(last))
    if (segments.isEmpty()) segments += AskTextSegment.Text(text)
    return segments
}

data class AskSourceCard(
    val noteId: String,
    val chunkIndex: Int,
    val title: String,
    val text: String,
)

/**
 * One source card per cited note, in citation order — a note
 * cited through two chunks still gets one card, snippeted
 * from its first cited chunk. The excerpt text comes from
 * the excerpts actually sent, so the snippet is the exact
 * passage the model saw; a citation whose excerpt is missing
 * (defensive — the parser only emits citations into the sent
 * list) is dropped, never rendered as an empty card.
 */
fun sourceCardsFor(
    sentExcerpts: List<AskExcerpt>,
    citations: List<AskCitation>,
): List<AskSourceCard> {
    val cards = mutableListOf<AskSourceCard>()
    val seen = mutableSetOf<String>()
    for (citation in citations) {
        if (citation.noteId in seen) continue
        val excerpt = sentExcerpts.firstOrNull {
            it.noteId == citation.noteId && it.chunkIndex == citation.chunkIndex
        } ?: continue
        seen += citation.noteId
        cards += AskSourceCard(
            noteId = excerpt.noteId,
            chunkIndex = excerpt.chunkIndex,
            title = excerpt.title,
            text = excerpt.text,
        )
    }
    return cards
}
