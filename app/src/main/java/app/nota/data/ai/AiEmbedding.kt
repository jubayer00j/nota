package app.nota.data.ai

/**
 * Embeddings (Phase 4.2) — the sibling capability the 4.1 provider
 * layer was shaped for. Chat stays [AiProvider.complete]; semantic
 * search rides on [AiEmbeddingProvider], which both wire shapes
 * also implement. Same rules as chat: calls go through the one
 * injectable [AiHttpClient] (tests and CI use fakes — the network
 * is never touched there), and every failure maps onto the 4.1
 * [AiException] taxonomy via the shared postOrThrow/mapHttpError.
 */

/** Whether a text is being embedded as a searchable document or
 *  as the search query itself. Gemini's API takes a task type and
 *  embeds the two differently (1.x's "document" | "query"); the
 *  OpenAI-compatible shape has no such notion and ignores it. */
enum class EmbedKind { DOCUMENT, QUERY }

/**
 * One effective embedding configuration: the provider, the key,
 * the EMBEDDING model (a slot field of its own — chat and
 * embedding models are different models with different names),
 * and the endpoint override for Custom.
 */
data class AiEmbeddingConfig(
    val providerId: AiProviderId,
    val apiKey: String,
    val embeddingModel: String,
    val baseUrl: String? = null,
) {
    /** The endpoint calls go to (normalized); null for Gemini. */
    fun resolvedBaseUrl(): String? = when (providerId) {
        AiProviderId.GEMINI -> null
        AiProviderId.CUSTOM -> normalizeAiBaseUrl(baseUrl).ifBlank { null }
        else -> providerId.presetBaseUrl
    }

    /** The embedding model a call will use: the configured one,
     *  or the provider's embedding default when the slot never
     *  stored one (blank for Custom = not configured). */
    fun resolvedEmbeddingModel(): String =
        embeddingModel.trim().ifBlank { providerId.defaultEmbeddingModel }

    /**
     * The identity of the embedding space this config writes
     * into (1.x's providerKey, minus dims): provider, endpoint,
     * embedding model. Vectors from two spaces are never mixed
     * or compared — the semantic index stores this signature and
     * treats anything written under a different one as absent.
     */
    fun spaceSignature(): String =
        "${providerId.storageId}|${resolvedBaseUrl() ?: "native"}|${resolvedEmbeddingModel()}"
}

/** A provider that can turn text into vectors. */
interface AiEmbeddingProvider {
    val id: AiProviderId

    /** Embeds ONE text. [AiException] on any failure. */
    suspend fun embed(config: AiEmbeddingConfig, text: String, kind: EmbedKind): FloatArray

    /** Embeds several texts, results in input order. The default
     *  is one call per text; the OpenAI-compatible shape overrides
     *  with its array-input endpoint (one call for the batch). */
    suspend fun embedAll(
        config: AiEmbeddingConfig,
        texts: List<String>,
        kind: EmbedKind,
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>(texts.size)
        for (text in texts) out += embed(config, text, kind)
        return out
    }
}

/** Builds the embedding implementation for a config's provider id. */
object AiEmbeddings {
    fun create(id: AiProviderId, http: AiHttpClient): AiEmbeddingProvider = when (id) {
        AiProviderId.GEMINI -> GeminiProvider(http)
        else -> OpenAiCompatibleProvider(id, http)
    }
}

/** Parses a JSON array of numbers into a vector; null when any
 *  element is not a number or the array is empty (a provider that
 *  answers 200 with a shapeless body is a BAD_RESPONSE, never a
 *  silently stored empty vector). */
internal fun parseEmbeddingVector(values: org.json.JSONArray?): FloatArray? {
    if (values == null || values.length() == 0) return null
    val out = FloatArray(values.length())
    for (i in 0 until values.length()) {
        val v = values.opt(i)
        if (v !is Number) return null
        out[i] = v.toFloat()
    }
    return out
}
