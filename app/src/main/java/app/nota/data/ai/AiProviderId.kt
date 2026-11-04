package app.nota.data.ai

/**
 * The providers Nota's BYOK layer can talk to (Phase 4.1) — the
 * native form of Noma 1.x's provider table (PRs #84–#87 there).
 *
 * Two wire shapes cover everything: Gemini's native generateContent
 * API, and one OpenAI-compatible chat-completions shape shared by
 * the OpenAI / OpenRouter presets and any custom endpoint. A native
 * Anthropic client stays deferred, exactly as in 1.x (Anthropic is
 * reachable meanwhile through OpenRouter).
 *
 * Every provider owns its OWN key + model slot in the settings
 * store (1.x #87): switching providers can never send the previous
 * provider's key to the new one, and switching back restores that
 * provider's values untouched.
 */
enum class AiProviderId(
    /** Stable storage id — never rename once shipped. */
    val storageId: String,
    val label: String,
    /** Fixed endpoint for the preset; null for Gemini (native API)
     *  and for Custom (the endpoint is the user's own, stored). */
    val presetBaseUrl: String?,
    /** Model pre-filled for a fresh slot; the user can overwrite. */
    val defaultModel: String,
    /** Embedding model pre-filled for a fresh slot (Phase 4.2);
     *  the user can overwrite. Blank for Custom: the endpoint is
     *  the user's own, so no model name can be presumed. */
    val defaultEmbeddingModel: String,
    /** True when calls take the OpenAI-compatible wire shape. */
    val openAiCompatible: Boolean,
) {
    GEMINI(
        storageId = "gemini",
        label = "Gemini",
        presetBaseUrl = null,
        defaultModel = "gemini-3.5-flash-lite",
        defaultEmbeddingModel = "gemini-embedding-001",
        openAiCompatible = false,
    ),
    OPENAI(
        storageId = "openai",
        label = "OpenAI",
        presetBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        defaultEmbeddingModel = "text-embedding-3-large",
        openAiCompatible = true,
    ),
    OPENROUTER(
        storageId = "openrouter",
        label = "OpenRouter",
        presetBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
        defaultEmbeddingModel = "text-embedding-3-large",
        openAiCompatible = true,
    ),
    CUSTOM(
        storageId = "custom",
        label = "Custom endpoint",
        presetBaseUrl = null,
        defaultModel = "",
        defaultEmbeddingModel = "",
        openAiCompatible = true,
    ),
    ;

    companion object {
        fun fromStorageId(id: String?): AiProviderId =
            entries.firstOrNull { it.storageId == id } ?: GEMINI
    }
}

/** Normalizes a stored/typed base URL: trimmed, no trailing slashes
 *  (1.x's normalizeBaseUrl — comparisons and request building both
 *  use the normalized form). */
fun normalizeAiBaseUrl(baseUrl: String?): String =
    (baseUrl ?: "").trim().trimEnd('/')
