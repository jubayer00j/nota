package app.nota.data.ai

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * OpenAI-compatible provider behaviour (Phase 4.1) against a fake
 * transport: chat-completions request shape for the OpenAI and
 * OpenRouter presets and a custom endpoint, reply parsing (string
 * and parts-array content), config guards that fail BEFORE any
 * request, and the error-class mapping incl. the OpenAI error
 * envelope.
 */
@RunWith(RobolectricTestRunner::class)
class OpenAiCompatibleProviderTest {

    private val http = FakeAiHttpClient()

    private fun okBody(text: String): String = JSONObject()
        .put(
            "choices",
            JSONArray().put(
                JSONObject().put(
                    "message",
                    JSONObject().put("role", "assistant").put("content", text),
                ),
            ),
        )
        .toString()

    private fun provider(id: AiProviderId) = OpenAiCompatibleProvider(id, http)

    private fun config(
        id: AiProviderId,
        key: String = "sk-test-1",
        model: String = "gpt-4o-mini",
        baseUrl: String? = null,
    ) = AiConfig(id, apiKey = key, model = model, baseUrl = baseUrl)

    @Test
    fun openAiRequestShape() = runBlocking {
        http.next = AiHttpResponse(200, okBody("done"))
        val out = provider(AiProviderId.OPENAI).complete(config(AiProviderId.OPENAI), "Hi there")
        assertEquals("done", out)
        val call = http.calls.single()
        assertEquals("https://api.openai.com/v1/chat/completions", call.url)
        assertEquals("Bearer sk-test-1", call.headers["Authorization"])
        val body = JSONObject(call.body)
        assertEquals("gpt-4o-mini", body.getString("model"))
        val message = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", message.getString("role"))
        assertEquals("Hi there", message.getString("content"))
    }

    @Test
    fun openRouterGetsIdentificationHeaders() = runBlocking {
        http.next = AiHttpResponse(200, okBody("ok"))
        provider(AiProviderId.OPENROUTER).complete(
            config(AiProviderId.OPENROUTER, model = "openai/gpt-4o-mini"),
            "p",
        )
        val call = http.calls.single()
        assertEquals("https://openrouter.ai/api/v1/chat/completions", call.url)
        assertEquals("Nota", call.headers["X-Title"])
        assertFalse(call.headers["HTTP-Referer"].isNullOrBlank())
    }

    @Test
    fun customEndpointTrailingSlashIsNormalized() = runBlocking {
        http.next = AiHttpResponse(200, okBody("ok"))
        provider(AiProviderId.CUSTOM).complete(
            config(AiProviderId.CUSTOM, baseUrl = "https://llm.example.com/v1/"),
            "p",
        )
        assertEquals("https://llm.example.com/v1/chat/completions", http.calls.single().url)
    }

    @Test
    fun blankModelOnPresetFallsBackToItsDefault() = runBlocking {
        // Jules review (Phase 4.1): the compatible client used to
        // reject a blank model outright while Gemini resolved it
        // to the provider default — so clearing the model field
        // broke OpenAI/OpenRouter calls. A blank model now
        // resolves via AiConfig.resolvedModel, exactly like
        // Gemini, and the request carries the default.
        http.next = AiHttpResponse(200, okBody("done"))
        val out = provider(AiProviderId.OPENAI).complete(
            config(AiProviderId.OPENAI, model = " "),
            "Hi there",
        )
        assertEquals("done", out)
        val body = JSONObject(http.calls.single().body)
        assertEquals(AiProviderId.OPENAI.defaultModel, body.getString("model"))
    }

    @Test
    fun partsArrayContentIsJoined() = runBlocking {
        val body = JSONObject()
            .put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", "Hello "))
                                .put(JSONObject().put("type", "text").put("text", "there")),
                        ),
                    ),
                ),
            )
            .toString()
        http.next = AiHttpResponse(200, body)
        assertEquals(
            "Hello there",
            provider(AiProviderId.OPENAI).complete(config(AiProviderId.OPENAI), "p"),
        )
    }

    @Test
    fun configGuardsFailBeforeAnyRequest() = runBlocking {
        // No endpoint at all (custom without a URL).
        try {
            provider(AiProviderId.CUSTOM).complete(config(AiProviderId.CUSTOM), "p")
            fail("expected NOT_CONFIGURED")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NOT_CONFIGURED, e.kind)
        }
        // Insecure endpoint.
        try {
            provider(AiProviderId.CUSTOM).complete(
                config(AiProviderId.CUSTOM, baseUrl = "http://llm.example.com/v1"),
                "p",
            )
            fail("expected NOT_CONFIGURED")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NOT_CONFIGURED, e.kind)
        }
        // No model — on a provider with no default to resolve
        // to (Custom's default model is blank). A blank model on
        // a PRESET resolves to the preset's default instead
        // (blankModelOnPresetFallsBackToItsDefault below).
        try {
            provider(AiProviderId.CUSTOM).complete(
                config(
                    AiProviderId.CUSTOM,
                    model = " ",
                    baseUrl = "https://llm.example.com/v1",
                ),
                "p",
            )
            fail("expected NOT_CONFIGURED")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NOT_CONFIGURED, e.kind)
        }
        // No key.
        try {
            provider(AiProviderId.OPENAI).complete(
                config(AiProviderId.OPENAI, key = ""),
                "p",
            )
            fail("expected NO_KEY")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NO_KEY, e.kind)
        }
        // Unparseable URL behind an https:// prefix — must be a
        // configuration error, never an unmapped crash from the
        // HTTP layer (Codex review, Phase 4.1).
        try {
            provider(AiProviderId.CUSTOM).complete(
                config(AiProviderId.CUSTOM, baseUrl = "https://["),
                "p",
            )
            fail("expected NOT_CONFIGURED")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NOT_CONFIGURED, e.kind)
        }
        assertTrue("no request may leave the device", http.calls.isEmpty())
    }

    @Test
    fun statusMapping_401_403_429_404_502() = runBlocking {
        val cases = listOf(
            401 to AiErrorKind.INVALID_KEY,
            403 to AiErrorKind.INVALID_KEY,
            429 to AiErrorKind.RATE_LIMITED,
            404 to AiErrorKind.MODEL_UNAVAILABLE,
            502 to AiErrorKind.TRANSIENT,
        )
        for ((status, kind) in cases) {
            http.next = AiHttpResponse(status, "{}")
            try {
                provider(AiProviderId.OPENAI).complete(config(AiProviderId.OPENAI), "p")
                fail("expected AiException for $status")
            } catch (e: AiException) {
                assertEquals("status $status", kind, e.kind)
            }
        }
    }

    @Test
    fun badRequestUsesEnvelopeNotInvalidKey() = runBlocking {
        // The compatible shape reserves 400 for the envelope path
        // (unlike Gemini): the provider's own words surface.
        http.next = AiHttpResponse(
            400,
            """{"error":{"message":"max_tokens is not supported for this model","type":"invalid_request_error"}}""",
        )
        try {
            provider(AiProviderId.OPENAI).complete(config(AiProviderId.OPENAI), "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.API_ERROR, e.kind)
            assertEquals("max_tokens is not supported for this model", e.upstreamMessage)
        }
    }

    @Test
    fun emptyContentIsEmptyKind() = runBlocking {
        http.next = AiHttpResponse(200, okBody("   "))
        try {
            provider(AiProviderId.OPENAI).complete(config(AiProviderId.OPENAI), "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.EMPTY, e.kind)
        }
        http.next = AiHttpResponse(200, """{"choices":[]}""")
        try {
            provider(AiProviderId.OPENAI).complete(config(AiProviderId.OPENAI), "p")
            fail("expected AiException")
        } catch (e: AiException) {
            // No first choice: there is no readable reply at all.
            assertEquals(AiErrorKind.EMPTY, e.kind)
        }
    }

    // ---- Embeddings (Phase 4.2) -----------------------------------

    private fun embedConfig(
        id: AiProviderId,
        key: String = "sk-test-1",
        embeddingModel: String = "",
        baseUrl: String? = null,
    ) = AiEmbeddingConfig(id, apiKey = key, embeddingModel = embeddingModel, baseUrl = baseUrl)

    private fun embedOkBody(vectors: List<List<Double>>): String {
        val data = JSONArray()
        vectors.forEachIndexed { index, values ->
            val arr = JSONArray()
            values.forEach { arr.put(it) }
            data.put(JSONObject().put("index", index).put("embedding", arr))
        }
        return JSONObject().put("data", data).toString()
    }

    @Test
    fun embedAllBatchesIntoOneCallAndParsesInOrder() = runBlocking {
        http.next = AiHttpResponse(200, embedOkBody(listOf(listOf(0.1, 0.2), listOf(0.3, 0.4))))
        val out = provider(AiProviderId.OPENAI)
            .embedAll(embedConfig(AiProviderId.OPENAI), listOf("first", "second"), EmbedKind.DOCUMENT)
        assertEquals(2, out.size)
        assertEquals(0.1f, out[0][0])
        assertEquals(0.4f, out[1][1])
        // ONE call for the whole batch, array input, default model.
        val call = http.calls.single()
        assertEquals("https://api.openai.com/v1/embeddings", call.url)
        assertEquals("Bearer <redacted>", call.headers["Authorization"])
        val body = JSONObject(call.body)
        assertEquals("text-embedding-3-large", body.getString("model"))
        val input = body.getJSONArray("input")
        assertEquals("first", input.getString(0))
        assertEquals("second", input.getString(1))
    }

    @Test
    fun embedOutOfOrderDataIsReassembledByIndex() = runBlocking {
        // The reply's entries arrive shuffled; the index field,
        // not the array position, decides which vector is whose.
        val body = JSONObject().put(
            "data",
            JSONArray()
                .put(JSONObject().put("index", 1).put("embedding", JSONArray().put(9.0).put(9.0)))
                .put(JSONObject().put("index", 0).put("embedding", JSONArray().put(1.0).put(1.0))),
        ).toString()
        http.next = AiHttpResponse(200, body)
        val out = provider(AiProviderId.OPENAI)
            .embedAll(embedConfig(AiProviderId.OPENAI), listOf("a", "b"), EmbedKind.DOCUMENT)
        assertEquals(1.0f, out[0][0])
        assertEquals(9.0f, out[1][0])
    }

    @Test
    fun embedCountMismatchIsBadResponse() = runBlocking {
        http.next = AiHttpResponse(200, embedOkBody(listOf(listOf(0.1))))
        try {
            provider(AiProviderId.OPENAI)
                .embedAll(embedConfig(AiProviderId.OPENAI), listOf("a", "b"), EmbedKind.DOCUMENT)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.BAD_RESPONSE, e.kind)
        }
    }

    @Test
    fun embedCustomBlankModelIsNotConfiguredBeforeAnyRequest() = runBlocking {
        try {
            provider(AiProviderId.CUSTOM).embed(
                embedConfig(
                    AiProviderId.CUSTOM,
                    embeddingModel = "",
                    baseUrl = "https://llm.example.com/v1",
                ),
                "x",
                EmbedKind.DOCUMENT,
            )
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NOT_CONFIGURED, e.kind)
        }
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun embedUnauthorizedMapsToInvalidKey() = runBlocking {
        http.next = AiHttpResponse(401, "{}")
        try {
            provider(AiProviderId.OPENAI)
                .embed(embedConfig(AiProviderId.OPENAI), "x", EmbedKind.QUERY)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.INVALID_KEY, e.kind)
        }
    }
}
