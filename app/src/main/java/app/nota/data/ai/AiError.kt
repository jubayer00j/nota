package app.nota.data.ai

/**
 * AI failure classes (Phase 4.1) — the native form of 1.x's
 * AiErrorKind table. Every provider failure maps to exactly one
 * kind, and each kind carries ONE calm sentence for the UI.
 *
 * The 1.x lesson (PRs #84–#85 + the 2026-10-24 incident): a provider
 * failure must never collapse into a generic "something failed" —
 * and when the provider sent its own error envelope, its message is
 * preserved (see [AiException.upstreamMessage]) so the real cause
 * ("API key not valid", "quota exceeded"…) is diagnosable instead
 * of swallowed. The API key itself is never part of any message:
 * the envelope reader drops a message that echoes the key.
 */
enum class AiErrorKind(val userMessage: String) {
    NO_KEY("Add your API key in Settings → AI features to use AI."),
    INVALID_KEY("That key didn't work — check it and try again."),
    RATE_LIMITED("Rate limit reached — try again in a little while."),
    OFFLINE("You're offline — AI needs a connection."),
    BAD_RESPONSE("The provider sent back something Nota couldn't read. Try again."),
    EMPTY("The provider didn't return anything for this note. Try again."),
    MODEL_UNAVAILABLE("That model isn't available through the provider right now. Try again later."),
    TRANSIENT("The provider is busy right now — try again in a little while."),
    API_ERROR("The provider couldn't complete that request. Try again."),
    NOT_CONFIGURED("Finish setting up this provider in Settings → AI features first."),
}

/**
 * The one exception type the AI layer throws. [kind] decides the
 * calm UI sentence; [upstreamMessage] optionally carries the
 * provider's own envelope message for display after the calm
 * sentence and for (key-free) diagnostics.
 */
class AiException(
    val kind: AiErrorKind,
    message: String = kind.userMessage,
    val upstreamMessage: String? = null,
) : Exception(message) {

    /** The full user-facing line: the calm sentence, plus the
     *  provider's own words when they were preserved. */
    val displayMessage: String
        get() = if (upstreamMessage.isNullOrBlank()) {
            message ?: kind.userMessage
        } else {
            "${message ?: kind.userMessage} (${upstreamMessage})"
        }

    companion object {
        fun noKey() = AiException(AiErrorKind.NO_KEY)

        fun notConfigured(detail: String) =
            AiException(AiErrorKind.NOT_CONFIGURED, detail)

        fun empty(detail: String) = AiException(AiErrorKind.EMPTY, detail)
    }
}
