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
    /** Beginner AI Setup #1: a one-line plain description of the
     *  provider for the picker (who it is / who it suits). Lives
     *  here, beside the label, so every surface that names a
     *  provider describes it the same way. */
    val description: String,
    /** Beginner AI Setup #1: where a user creates a key for this
     *  provider (the same key pages 1.x's table named). Null for
     *  Custom — there is no page Nota can point at. */
    val keyPageUrl: String?,
    /** Beginner AI Setup #1: one honest line about where this
     *  provider's key comes from. Any offer in it is the
     *  PROVIDER's, attributed — never Nota's own guarantee. */
    val keyHelpNote: String,
    /** Beginner AI Setup #1: the 2–3 short steps to a key, shown
     *  under the API-key field. Empty for Custom (its note says
     *  all that can honestly be said). */
    val keySteps: List<String>,
) {
    GEMINI(
        storageId = "gemini",
        label = "Gemini",
        presetBaseUrl = null,
        defaultModel = "gemini-3.5-flash-lite",
        defaultEmbeddingModel = "gemini-embedding-001",
        openAiCompatible = false,
        description = "Google's AI — the suggested place to start.",
        keyPageUrl = "https://aistudio.google.com/app/apikey",
        keyHelpNote = "A Gemini key comes from Google AI Studio — Google " +
            "offers keys there; their page shows the current terms.",
        keySteps = listOf(
            "Open Google AI Studio and sign in with your Google account.",
            "Choose “Create API key”.",
            "Copy the key and paste it here.",
        ),
    ),
    OPENAI(
        storageId = "openai",
        label = "OpenAI",
        presetBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        defaultEmbeddingModel = "text-embedding-3-large",
        openAiCompatible = true,
        description = "ChatGPT's maker — usage is billed by OpenAI.",
        keyPageUrl = "https://platform.openai.com/api-keys",
        keyHelpNote = "An OpenAI key comes from your OpenAI account.",
        keySteps = listOf(
            "Open OpenAI's API keys page and sign in.",
            "Choose “Create new secret key”.",
            "Copy the key and paste it here — OpenAI shows it only once.",
        ),
    ),
    OPENROUTER(
        storageId = "openrouter",
        label = "OpenRouter",
        presetBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-4o-mini",
        // Beginner AI Setup #1 (item 8): prefixed with the owning
        // provider's namespace, as OpenRouter's own model page for
        // this model instructs ("Use `openai/text-embedding-3-large`
        // with the OpenRouter API" — openrouter.ai/openai/
        // text-embedding-3-large, checked 2026-11-08). 1.x's table
        // already carried the prefixed form. Slots saved by
        // 0.14.0 or earlier hold the unprefixed string instead;
        // AiSettingsStore migrates exactly that value on read
        // (LEGACY_OPENROUTER_EMBEDDING_MODEL, fix round 5).
        defaultEmbeddingModel = "openai/text-embedding-3-large",
        openAiCompatible = true,
        description = "One key that can reach models from many providers.",
        keyPageUrl = "https://openrouter.ai/keys",
        keyHelpNote = "An OpenRouter key comes from your OpenRouter account.",
        keySteps = listOf(
            "Open OpenRouter's keys page and sign in.",
            "Choose “Create Key”.",
            "Copy the key and paste it here.",
        ),
    ),
    CUSTOM(
        storageId = "custom",
        label = "Custom endpoint",
        presetBaseUrl = null,
        defaultModel = "",
        defaultEmbeddingModel = "",
        openAiCompatible = true,
        description = "Advanced — any OpenAI-compatible service " +
            "you point Nota at.",
        keyPageUrl = null,
        keyHelpNote = "Keys for a custom endpoint live in that service's " +
            "own dashboard — check its documentation for where to " +
            "create one.",
        keySteps = emptyList(),
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
