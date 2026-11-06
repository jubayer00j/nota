package app.nota.data.ai

/**
 * Ask Nota — prompt assembly and answer parsing (Phase 4.3), the
 * native port of 1.x's ask.ts (PRs #99–#105 there). Retrieval
 * lives in [SemanticIndexer.retrieveAskExcerpts]; generation
 * rides the ordinary 4.1 chat seam via [AiService.ask]. Nothing
 * here is ambient: every function runs only because the user
 * asked a question.
 *
 * The grounding contract this module enforces:
 * - The prompt carries ONLY numbered excerpts from the user's
 *   own notes, inside hard budgets (below), and instructs the
 *   model to answer from them alone, cite every claim, and
 *   reply with [ASK_REFUSAL_SENTENCE] when they don't answer
 *   the question.
 * - The parser trusts nothing back: the refusal sentence is a
 *   refusal; a citation number the prompt never carried is
 *   stripped from the text, never rendered; and a reply with
 *   NO valid citation at all degrades to the same refusal —
 *   Ask's promise is grounded-or-silent, and uncited text is
 *   indistinguishable from an ungrounded invention.
 */

/**
 * The exact sentence the model must reply with when the
 * excerpts do not answer the question — and the string the
 * parser keys on. The UI renders its own honest empty state
 * from the typed refusal; this sentence itself is never
 * presented as an "answer".
 */
const val ASK_REFUSAL_SENTENCE = "I couldn't find that in your notes."

/** At most this many excerpts ride along in one prompt. */
const val ASK_MAX_EXCERPTS = 6

/** At most this many chunks from any single note — diversity
 *  beats depth. */
const val ASK_MAX_EXCERPTS_PER_NOTE = 2

/** Character sub-budget for excerpt text inside the overall
 *  AI cap. */
const val ASK_EXCERPT_BUDGET_CHARS = 16_000

/** Character budget for a source note's TITLE inside the
 *  prompt. A title is interpolated into its excerpt's line,
 *  so an unbounded one (a pasted document as a "title")
 *  defeats the total cap no matter how the text budgets shed
 *  — the shrink passes can only shrink excerpt text and the
 *  question, never the title. Titles are therefore
 *  middle-truncated to this before any budget pass runs, and
 *  the whole-prompt passes measure the assembled prompt with
 *  titles included, so the cap then holds for every input
 *  (Codex PR #25 P2). */
const val ASK_TITLE_BUDGET_CHARS = 200

/** Character cap for the optional previous Q/A pair (context,
 *  never evidence). */
const val ASK_PREVIOUS_TURN_BUDGET_CHARS = 2_000

/** One retrieved passage, ready to be numbered into a prompt. */
data class AskExcerpt(
    val noteId: String,
    val chunkIndex: Int,
    /** The source note's title, as it stood at retrieval time. */
    val title: String,
    /** The chunk's plain text. */
    val text: String,
    /** Cosine similarity against the question. */
    val similarity: Double,
)

/** A validated citation: excerpt number [n] in the prompt ↔
 *  its source chunk. */
data class AskCitation(
    val n: Int,
    val noteId: String,
    val chunkIndex: Int,
)

/** The immediately previous exchange, carried as context. */
data class AskPreviousTurn(val question: String, val answer: String)

/** The typed outcome of an Ask exchange. */
sealed interface AskAnswerResult {
    /** A grounded answer: the model's text (citations already
     *  validated and normalised by the parser) and the
     *  citations it earned, in first-use order. */
    data class Answer(
        val text: String,
        val citations: List<AskCitation>,
    ) : AskAnswerResult

    /** The model refused, or its reply carried no valid
     *  citation — either way, nothing ungrounded is shown. */
    data object Refusal : AskAnswerResult

    /** Decided BEFORE any provider call: retrieval found
     *  nothing at or above the floor, so there was nothing to
     *  ground an answer in and no tokens were spent. Never
     *  produced by the parser. */
    data object NoEvidence : AskAnswerResult
}

/** An assembled prompt plus the excerpts it actually carries,
 *  in prompt order — budget trimming may have dropped some of
 *  the retrieved excerpts. Citation numbers in the model's
 *  reply index into this list. */
data class AskPrompt(
    val prompt: String,
    val excerpts: List<AskExcerpt>,
)

object AskNota {

    /**
     * [truncateAiInput]'s elision marker adds ~48 chars beyond
     * the cap it is given, so budget calls in this module leave
     * room for it — the caps here are hard caps, not
     * approximations (the 1.x ELISION_ALLOWANCE).
     */
    private const val ELISION_ALLOWANCE = 64

    private fun truncateTo(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return truncateAiInput(text, maxOf(1, maxChars - ELISION_ALLOWANCE)).first
    }

    private val ASK_INSTRUCTIONS = listOf(
        "You are Ask Nota, answering a question using only excerpts from the user's own notes.",
        "",
        "Rules:",
        "- Answer using ONLY the numbered excerpts below. Do not use outside knowledge.",
        "- Cite every claim with its excerpt number, like [1] — always with Western digits (0-9), whatever language you answer in.",
        "- If the excerpts do not answer the question, reply with exactly this sentence and nothing else: $ASK_REFUSAL_SENTENCE",
        "- Answer in the language of the question.",
        "- Be concise.",
    ).joinToString("\n")

    /**
     * Assembles the grounded prompt. Budgets, in order: titles
     * are bounded to [ASK_TITLE_BUDGET_CHARS] first (see its
     * doc — the shrink passes below can only shrink excerpt
     * text and the question, so an unbounded title defeats
     * them all); excerpt
     * text is capped at [ASK_EXCERPT_BUDGET_CHARS] by dropping
     * the lowest-ranked chunks whole (a chunk is never cut
     * mid-text; a lone chunk that still cannot fit is
     * middle-truncated, elision marker visible to the model);
     * the whole prompt is capped at [AI_MAX_INPUT_CHARS] by the
     * same shedding, then by truncation — and those passes
     * measure the ASSEMBLED prompt, titles and framing
     * included, so the cap holds for ALL inputs, pathological
     * titles included. The previous Q/A pair, when given, is added
     * only with whatever budget remains after excerpts,
     * truncated to [ASK_PREVIOUS_TURN_BUDGET_CHARS] and
     * labelled as conversation — never as evidence. The
     * returned excerpts carry the bounded titles, so citation
     * mapping and source cards describe the real prompt.
     */
    fun buildAskPrompt(
        question: String,
        excerpts: List<AskExcerpt>,
        previousTurn: AskPreviousTurn? = null,
    ): AskPrompt {
        var used = excerpts.map {
            it.copy(title = truncateTo(it.title, ASK_TITLE_BUDGET_CHARS))
        }

        fun excerptChars(list: List<AskExcerpt>): Int = list.sumOf { it.text.length }
        while (used.size > 1 && excerptChars(used) > ASK_EXCERPT_BUDGET_CHARS) {
            used = used.dropLast(1)
        }
        if (used.size == 1 && used[0].text.length > ASK_EXCERPT_BUDGET_CHARS) {
            used = listOf(used[0].copy(text = truncateTo(used[0].text, ASK_EXCERPT_BUDGET_CHARS)))
        }

        fun excerptBlock(list: List<AskExcerpt>): String =
            list.mapIndexed { i, excerpt ->
                "[${i + 1}] From \"${excerpt.title}\": ${excerpt.text}"
            }.joinToString("\n\n")

        fun assemble(list: List<AskExcerpt>, conversation: String?, q: String): String =
            buildList {
                add(ASK_INSTRUCTIONS)
                if (conversation != null) {
                    add("")
                    add(conversation)
                }
                add("")
                add("Excerpts from the user's notes:")
                add(excerptBlock(list))
                add("")
                add("Question: $q")
            }.joinToString("\n")

        var prompt = assemble(used, null, question)
        while (used.size > 1 && prompt.length > AI_MAX_INPUT_CHARS) {
            used = used.dropLast(1)
            prompt = assemble(used, null, question)
        }
        if (used.size == 1 && prompt.length > AI_MAX_INPUT_CHARS) {
            // One excerpt left and still over: shrink its text
            // to whatever room the fixed parts leave (the
            // sub-budget pass above means this needs a very long
            // question, not a very long chunk).
            val room = AI_MAX_INPUT_CHARS - (prompt.length - used[0].text.length)
            val text = when {
                room <= 0 -> ""
                room < used[0].text.length -> truncateTo(used[0].text, room)
                else -> used[0].text
            }
            used = listOf(used[0].copy(text = text))
            prompt = assemble(used, null, question)
        }
        if (prompt.length > AI_MAX_INPUT_CHARS) {
            // The question itself is the overflow (defensive;
            // unreachable from the UI): middle-truncate it so
            // the cap always holds.
            val room = maxOf(0, AI_MAX_INPUT_CHARS - (prompt.length - question.length))
            prompt = assemble(used, null, truncateTo(question, room))
        }

        if (previousTurn != null) {
            val raw = listOf(
                "Earlier conversation (context only — not evidence):",
                "Q: ${previousTurn.question}",
                "A: ${previousTurn.answer}",
            ).joinToString("\n")
            val capped = if (raw.length > ASK_PREVIOUS_TURN_BUDGET_CHARS) {
                truncateTo(raw, ASK_PREVIOUS_TURN_BUDGET_CHARS)
            } else {
                raw
            }
            val withConversation = assemble(used, capped, question)
            if (withConversation.length <= AI_MAX_INPUT_CHARS) prompt = withConversation
        }

        return AskPrompt(prompt, used)
    }

    /** "০" (U+09E6) — Bengali digit zero; the block ০–৯ is
     *  contiguous. */
    private const val BENGALI_DIGIT_ZERO = 0x09E6

    /** Parses a citation number written in Western or Bengali
     *  digits; -1 for anything unparseable (an absurdly long
     *  digit run is simply out of range). */
    private fun citationNumber(digits: String): Int {
        val out = StringBuilder()
        for (ch in digits) {
            val cp = ch.code
            out.append(
                if (cp in BENGALI_DIGIT_ZERO..BENGALI_DIGIT_ZERO + 9) {
                    '0' + (cp - BENGALI_DIGIT_ZERO)
                } else {
                    ch
                },
            )
        }
        return out.toString().toIntOrNull() ?: -1
    }

    private val CITATION_PATTERN = Regex("\\[([0-9\u09E6-\u09EF]+)\\]")

    /**
     * Turns the model's raw reply into a typed result. The
     * refusal sentence (matched at the head of the
     * whitespace-normalised reply) is a refusal. Otherwise,
     * `[n]` citations are validated against the excerpts
     * actually sent: an out-of-range number is a hallucination
     * and is stripped from the text, never rendered; valid
     * citations are returned deduplicated in first-use order,
     * mapped back to their source note + chunk. A reply with
     * NO valid citation at all is not an answer either (see
     * the module doc). Citation numbers may arrive in Bengali
     * digits ([১]) when the model answers in Bengali — 1.x's
     * live acceptance caught correct answers being discarded
     * for exactly that — so digits are normalised inside the
     * brackets before validation, and the rendered marker is
     * rewritten to Western digits; digits in the answer's
     * prose are untouched.
     */
    fun parseAskAnswer(raw: String, excerpts: List<AskExcerpt>): AskAnswerResult {
        val normalized = raw.trim().replace(Regex("\\s+"), " ")
        if (normalized.startsWith(ASK_REFUSAL_SENTENCE)) return AskAnswerResult.Refusal

        val citations = mutableListOf<AskCitation>()
        val seen = mutableSetOf<Int>()
        val text = CITATION_PATTERN.replace(raw) { match ->
            val n = citationNumber(match.groupValues[1])
            if (n < 1 || n > excerpts.size) {
                ""
            } else {
                if (seen.add(n)) {
                    val excerpt = excerpts[n - 1]
                    citations += AskCitation(n, excerpt.noteId, excerpt.chunkIndex)
                }
                "[$n]"
            }
        }.replace(Regex("[ \\t]{2,}"), " ").trim()
        if (citations.isEmpty()) return AskAnswerResult.Refusal
        return AskAnswerResult.Answer(text, citations)
    }
}
