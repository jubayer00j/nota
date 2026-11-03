package app.nota.data.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * One effective provider configuration — everything a single call
 * needs. Produced two ways, which is the whole point of the type:
 * from the SAVED slot (normal actions) or straight from the
 * Settings FORM's current field values (Test connection — the 1.x
 * wart was testing saved values instead of typed ones; here the
 * form builds this object from what's on screen, so what is tested
 * is exactly what the user sees).
 */
data class AiConfig(
    val providerId: AiProviderId,
    val apiKey: String,
    val model: String,
    /** Endpoint override — only meaningful for CUSTOM; presets
     *  resolve their fixed URL from [AiProviderId.presetBaseUrl]. */
    val baseUrl: String? = null,
) {
    /** The endpoint calls go to (normalized); null for Gemini. */
    fun resolvedBaseUrl(): String? = when (providerId) {
        AiProviderId.GEMINI -> null
        AiProviderId.CUSTOM -> normalizeAiBaseUrl(baseUrl).ifBlank { null }
        else -> providerId.presetBaseUrl
    }

    /** The model a call will use: the configured one, or the
     *  provider's default when the slot never stored one. */
    fun resolvedModel(): String =
        model.trim().ifBlank { providerId.defaultModel }
}

/**
 * A chat-completion provider (Phase 4.1): one prompt in, the
 * reply text out, [AiException] on any failure. Phase 4.2 adds
 * embeddings as a SIBLING capability interface that providers may
 * also implement — [complete] is deliberately not reshaped for it.
 */
interface AiProvider {
    val id: AiProviderId

    suspend fun complete(config: AiConfig, prompt: String): String
}

/** Builds the provider implementation for a config's provider id. */
object AiProviders {
    fun create(id: AiProviderId, http: AiHttpClient): AiProvider = when (id) {
        AiProviderId.GEMINI -> GeminiProvider(http)
        else -> OpenAiCompatibleProvider(id, http)
    }
}

/**
 * The provider's own error-envelope message, when a failed call
 * carries one (1.x's readProviderErrorMessage). Only the message
 * string is ever surfaced — never the key or the rest of the body.
 * Returns null when there is nothing usable: a non-JSON body, no
 * message, or a message that echoes the key itself (defensive — a
 * surfaced message must never become a key leak). Long messages
 * are capped so the UI keeps one calm line.
 */
internal fun readUpstreamErrorMessage(body: String, apiKey: String): String? {
    val root = try {
        JSONObject(body)
    } catch (e: Exception) {
        return null
    }
    val error = root.opt("error") ?: return null
    val raw = when (error) {
        is String -> error
        is JSONObject -> error.optString("message").ifBlank { null }
        else -> null
    } ?: return null
    val clean = raw.trim()
    if (clean.isEmpty()) return null
    if (apiKey.isNotBlank() && clean.contains(apiKey)) return null
    return if (clean.length > 240) clean.substring(0, 240) + "…" else clean
}

/**
 * The shared failure mapping (1.x, both clients). [invalidKeyStatuses]
 * differs per wire shape: Gemini also treats 400 as a key problem,
 * the compatible shape reserves it for the envelope path.
 */
internal fun mapHttpError(
    response: AiHttpResponse,
    apiKey: String,
    model: String,
    invalidKeyStatuses: Set<Int>,
): AiException = when {
    response.status == 429 -> AiException(AiErrorKind.RATE_LIMITED)
    response.status in invalidKeyStatuses -> AiException(AiErrorKind.INVALID_KEY)
    response.status == 404 -> AiException(
        AiErrorKind.MODEL_UNAVAILABLE,
        "The model \"$model\" isn't available through this provider right now. " +
            "Try again later, or report the model name if it keeps failing.",
    )
    response.status in setOf(500, 502, 503) -> AiException(AiErrorKind.TRANSIENT)
    else -> {
        val upstream = readUpstreamErrorMessage(response.body, apiKey)
        if (upstream != null) {
            AiException(
                AiErrorKind.API_ERROR,
                "Couldn't complete that request.",
                upstreamMessage = upstream,
            )
        } else {
            AiException(AiErrorKind.BAD_RESPONSE)
        }
    }
}

/** Runs one POST and returns the body on 2xx; maps every other
 *  outcome to its [AiException] kind. */
internal suspend fun postOrThrow(
    http: AiHttpClient,
    url: String,
    headers: Map<String, String>,
    jsonBody: String,
    apiKey: String,
    model: String,
    invalidKeyStatuses: Set<Int>,
): String {
    val response = try {
        http.post(url, headers, jsonBody)
    } catch (e: AiNetworkException) {
        throw AiException(AiErrorKind.OFFLINE)
    }
    if (response.status !in 200..299) {
        throw mapHttpError(response, apiKey, model, invalidKeyStatuses)
    }
    return response.body
}

/**
 * Gemini native client (1.x's generateContent). POST
 * v1beta/models/{model}:generateContent with the key in the
 * `x-goog-api-key` header; the reply text lives at
 * candidates[0].content.parts[].text, parts joined.
 */
class GeminiProvider(private val http: AiHttpClient) : AiProvider {

    override val id: AiProviderId = AiProviderId.GEMINI

    override suspend fun complete(config: AiConfig, prompt: String): String {
        val key = config.apiKey.trim()
        if (key.isBlank()) throw AiException.noKey()
        val model = config.resolvedModel()
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
            .toString()
        val responseBody = postOrThrow(
            http = http,
            url = "$API_BASE/models/$model:generateContent",
            headers = mapOf("x-goog-api-key" to key),
            jsonBody = body,
            apiKey = key,
            model = model,
            invalidKeyStatuses = setOf(400, 401, 403),
        )
        val root = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            throw AiException(AiErrorKind.BAD_RESPONSE)
        }
        val candidates = root.optJSONArray("candidates")
            ?: throw AiException(AiErrorKind.BAD_RESPONSE)
        if (candidates.length() == 0) throw AiException(AiErrorKind.EMPTY)
        val candidate = candidates.optJSONObject(0)
            ?: throw AiException(AiErrorKind.EMPTY)
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw AiException(AiErrorKind.BAD_RESPONSE)
        val text = buildString {
            for (i in 0 until parts.length()) {
                parts.optJSONObject(i)?.optString("text")?.let { append(it) }
            }
        }.trim()
        if (text.isEmpty()) throw AiException(AiErrorKind.EMPTY)
        return text
    }

    companion object {
        const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
    }
}

/**
 * OpenAI-compatible chat-completions client (1.x's
 * generateChatCompletion) — serves the OpenAI and OpenRouter
 * presets and any custom endpoint. POST {baseUrl}/chat/completions
 * with a Bearer key; reply text at choices[0].message.content (a
 * string, or a parts array whose text parts are joined). OpenRouter
 * additionally gets the identification headers its docs ask for.
 *
 * Config problems (no key, no/insecure endpoint, no model) fail
 * BEFORE any request, with a message that says what to fix.
 */
class OpenAiCompatibleProvider(
    override val id: AiProviderId,
    private val http: AiHttpClient,
) : AiProvider {

    override suspend fun complete(config: AiConfig, prompt: String): String {
        val key = config.apiKey.trim()
        if (key.isBlank()) throw AiException.noKey()
        val endpoint = config.resolvedBaseUrl()
        if (endpoint == null || !endpoint.startsWith("https://")) {
            throw AiException.notConfigured(
                "Set a provider endpoint URL starting with https:// in Settings → AI features.",
            )
        }
        val model = config.model.trim()
        if (model.isBlank()) {
            throw AiException.notConfigured(
                "Choose a model for this provider in Settings → AI features.",
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put("content", prompt),
                ),
            )
            .toString()
        val headers = mutableMapOf("Authorization" to "Bearer $key")
        if (id == AiProviderId.OPENROUTER) {
            headers["X-Title"] = "Nota"
            headers["HTTP-Referer"] = "https://github.com/jubayer00j/nota"
        }
        val responseBody = postOrThrow(
            http = http,
            url = "$endpoint/chat/completions",
            headers = headers,
            jsonBody = body,
            apiKey = key,
            model = model,
            invalidKeyStatuses = setOf(401, 403),
        )
        val root = try {
            JSONObject(responseBody)
        } catch (e: Exception) {
            throw AiException(AiErrorKind.BAD_RESPONSE)
        }
        val choices = root.optJSONArray("choices")
            ?: throw AiException(AiErrorKind.BAD_RESPONSE)
        val content = choices.optJSONObject(0)?.optJSONObject("message")?.opt("content")
        val text = when (content) {
            is String -> content
            is JSONArray -> buildString {
                for (i in 0 until content.length()) {
                    content.optJSONObject(i)?.optString("text")?.let { append(it) }
                }
            }
            else -> ""
        }.trim()
        if (text.isEmpty()) throw AiException(AiErrorKind.EMPTY)
        return text
    }
}
