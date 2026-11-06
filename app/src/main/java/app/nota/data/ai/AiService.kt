package app.nota.data.ai

/** The result of a Summarize call: the summary text, and whether
 *  the note was middle-truncated to fit the input budget. */
data class AiSummaryOutcome(val summary: String, val truncated: Boolean)

/** The result of a Suggest-tags call: parsed, cleaned tag names
 *  (already deduped against the note's current tags). */
data class AiTagOutcome(val tags: List<String>, val truncated: Boolean)

/**
 * The AI call orchestration (Phase 4.1) — the only door the UI
 * uses. Actions resolve the ACTIVE provider's SAVED config for the
 * current account and fail with [AiErrorKind.NO_KEY] (whose message
 * points at Settings) when there is none. [testConnection] is the
 * exception by design: it takes an explicit [AiConfig] — the
 * Settings form's current values — so a typed-but-unsaved key is
 * what gets tested (the 1.x wart, designed out).
 *
 * Calls happen only from here, only when the user asks: nothing
 * ambient, nothing automatic, nothing on a timer.
 */
class AiService(
    private val store: AiSettingsStore,
    private val http: AiHttpClient,
    private val accountIdProvider: () -> String,
) {

    /** The current account's saved active config, or null. */
    suspend fun currentConfig(): AiConfig? = store.activeConfig(accountIdProvider())

    /** Tests an EXPLICIT config (the Settings form's values —
     *  typed key included). True on success; [AiException] with
     *  the mapped kind otherwise. */
    suspend fun testConnection(config: AiConfig) {
        AiProviders.create(config.providerId, http)
            .complete(config, AiPrompts.TEST_PROMPT)
    }

    suspend fun summarize(title: String, plainText: String): AiSummaryOutcome {
        val config = currentConfig() ?: throw AiException.noKey()
        val (prepared, truncated) = truncateAiInput(plainText)
        if (prepared.isBlank()) {
            throw AiException.empty("This note has no text for AI to work with.")
        }
        val text = AiProviders.create(config.providerId, http)
            .complete(config, AiPrompts.summarize(title, prepared))
        return AiSummaryOutcome(text, truncated)
    }

    /**
     * Ask Nota's one generation call (Phase 4.3). Grounded or
     * silent: with no eligible excerpts there is nothing to
     * ground an answer in, so the result is
     * [AskAnswerResult.NoEvidence] and the provider is never
     * called — no tokens spent on a guaranteed refusal.
     * Otherwise the prompt is assembled by [AskNota] inside the
     * shared budget, the reply is parsed back into a typed
     * answer/refusal, and provider failures propagate as the
     * same [AiException] kinds every other AI action surfaces.
     * Retrieval lives in [SemanticIndexer.retrieveAskExcerpts];
     * this seam takes the excerpts it validated.
     */
    suspend fun ask(
        question: String,
        excerpts: List<AskExcerpt>,
        previousTurn: AskPreviousTurn? = null,
    ): AskAnswerResult {
        if (excerpts.isEmpty()) return AskAnswerResult.NoEvidence
        val config = currentConfig() ?: throw AiException.noKey()
        val built = AskNota.buildAskPrompt(question, excerpts, previousTurn)
        val text = AiProviders.create(config.providerId, http)
            .complete(config, built.prompt)
        return AskNota.parseAskAnswer(text, built.excerpts)
    }

    suspend fun suggestTags(
        title: String,
        plainText: String,
        existingTagNames: List<String>,
        currentTagNames: List<String>,
    ): AiTagOutcome {
        val config = currentConfig() ?: throw AiException.noKey()
        val (prepared, truncated) = truncateAiInput(plainText)
        if (prepared.isBlank() && title.isBlank()) {
            throw AiException.empty("This note has no text for AI to work with.")
        }
        val text = AiProviders.create(config.providerId, http)
            .complete(config, AiPrompts.suggestTags(title, prepared, existingTagNames, currentTagNames))
        return AiTagOutcome(TagListParser.parse(text, currentTagNames), truncated)
    }
}
