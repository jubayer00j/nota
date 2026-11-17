package app.nota.data.ai

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Gemini provider behaviour (Phase 4.1) against a fake transport —
 * no real network anywhere: request shape (URL, key header, body),
 * reply parsing, and the error-class mapping incl. Google's own
 * error envelope. Robolectric only because org.json needs a real
 * implementation; the provider itself is platform-free.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiProviderTest {

    private val http = FakeAiHttpClient()
    private val provider = GeminiProvider(http)
    private val config = AiConfig(AiProviderId.GEMINI, apiKey = "TEST-KEY-123", model = "")

    private fun okBody(text: String): String = JSONObject()
        .put(
            "candidates",
            org.json.JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        org.json.JSONArray().put(JSONObject().put("text", text)),
                    ),
                ),
            ),
        )
        .toString()

    @Test
    fun requestShape_urlHeaderBody() = runBlocking {
        http.next = AiHttpResponse(200, okBody("hello"))
        val out = provider.complete(config, "Summarize me")
        assertEquals("hello", out)
        val call = http.calls.single()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-3.5-flash-lite:generateContent",
            call.url,
        )
        assertEquals("TEST-KEY-123", call.headers["x-goog-api-key"])
        val body = JSONObject(call.body)
        val prompt = body.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertEquals("Summarize me", prompt)
    }

    @Test
    fun configuredModelIsUsed() = runBlocking {
        http.next = AiHttpResponse(200, okBody("x"))
        provider.complete(config.copy(model = "gemini-2.5-pro"), "p")
        assertTrue(http.calls.single().url.contains("models/gemini-2.5-pro:generateContent"))
    }

    @Test
    fun multiPartReplyIsJoined() = runBlocking {
        val body = JSONObject()
            .put(
                "candidates",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            org.json.JSONArray()
                                .put(JSONObject().put("text", "Hello "))
                                .put(JSONObject().put("text", "world")),
                        ),
                    ),
                ),
            )
            .toString()
        http.next = AiHttpResponse(200, body)
        assertEquals("Hello world", provider.complete(config, "p"))
    }

    @Test
    fun blankKeyFailsBeforeAnyRequest() = runBlocking {
        try {
            provider.complete(config.copy(apiKey = "  "), "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NO_KEY, e.kind)
        }
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun networkFailureMapsToOffline() = runBlocking {
        http.failure = AiNetworkException(java.io.IOException("no route"))
        try {
            provider.complete(config, "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.OFFLINE, e.kind)
        }
    }

    @Test
    fun timeoutMapsToTimeoutNotOffline() = runBlocking {
        // The request left the device; the provider was slow.
        // Reporting "offline" would blame a healthy connection
        // (Codex review, Phase 4.1).
        http.failure = AiNetworkException(
            java.net.SocketTimeoutException("read timed out"),
        )
        try {
            provider.complete(config, "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.TIMEOUT, e.kind)
        }
    }

    @Test
    fun statusMapping_429_400_403_404_503() = runBlocking {
        val cases = listOf(
            429 to AiErrorKind.RATE_LIMITED,
            400 to AiErrorKind.INVALID_KEY,
            401 to AiErrorKind.INVALID_KEY,
            403 to AiErrorKind.INVALID_KEY,
            404 to AiErrorKind.MODEL_UNAVAILABLE,
            500 to AiErrorKind.TRANSIENT,
            503 to AiErrorKind.TRANSIENT,
        )
        for ((status, kind) in cases) {
            http.next = AiHttpResponse(status, "{}")
            try {
                provider.complete(config, "p")
                fail("expected AiException for $status")
            } catch (e: AiException) {
                assertEquals("status $status", kind, e.kind)
            }
        }
    }

    @Test
    fun googleErrorEnvelopeMessageIsSurfaced() = runBlocking {
        // A status with no fixed mapping takes the envelope path:
        // the provider's own message is preserved for display.
        http.next = AiHttpResponse(
            409,
            """{"error":{"code":409,"message":"The request conflicts with a previous one.","status":"ABORTED"}}""",
        )
        try {
            provider.complete(config, "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.API_ERROR, e.kind)
            assertEquals("The request conflicts with a previous one.", e.upstreamMessage)
            assertTrue(e.displayMessage.contains("The request conflicts"))
        }
    }

    @Test
    fun envelopeMessageEchoingTheKeyIsDropped() = runBlocking {
        http.next = AiHttpResponse(
            409,
            """{"error":{"message":"bad key TEST-KEY-123 supplied"}}""",
        )
        try {
            provider.complete(config, "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.BAD_RESPONSE, e.kind)
            assertTrue(e.displayMessage.contains("TEST-KEY-123") == false)
        }
    }

    @Test
    fun malformedSuccessBodyIsBadResponse() = runBlocking {
        http.next = AiHttpResponse(200, "not json at all")
        try {
            provider.complete(config, "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.BAD_RESPONSE, e.kind)
        }
        http.next = AiHttpResponse(200, """{"somethingElse":true}""")
        try {
            provider.complete(config, "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.BAD_RESPONSE, e.kind)
        }
    }

    @Test
    fun emptyCandidatesIsEmptyKind() = runBlocking {
        http.next = AiHttpResponse(200, """{"candidates":[]}""")
        try {
            provider.complete(config, "p")
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.EMPTY, e.kind)
        }
    }

    // ---- Embeddings (Phase 4.2) -----------------------------------

    private val embedConfig = AiEmbeddingConfig(
        AiProviderId.GEMINI, apiKey = "TEST-KEY-123", embeddingModel = "",
    )

    private fun embedOkBody(vararg values: Double): String = JSONObject()
        .put(
            "embedding",
            JSONObject().put(
                "values",
                org.json.JSONArray().apply { values.forEach { put(it) } },
            ),
        )
        .toString()

    @Test
    fun embedRequestShapeAndParse() = runBlocking {
        http.next = AiHttpResponse(200, embedOkBody(0.25, -0.5, 1.0))
        val vector = provider.embed(embedConfig, "orchard notes", EmbedKind.DOCUMENT)
        assertEquals(3, vector.size)
        assertEquals(0.25f, vector[0])
        assertEquals(-0.5f, vector[1])
        assertEquals(1.0f, vector[2])
        val call = http.calls.single()
        // Blank configured model → the provider's embedding default.
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-embedding-001:embedContent",
            call.url,
        )
        assertEquals("TEST-KEY-123", call.headers["x-goog-api-key"])
        val body = JSONObject(call.body)
        assertEquals("RETRIEVAL_DOCUMENT", body.getString("taskType"))
        val text = body.getJSONObject("content").getJSONArray("parts")
            .getJSONObject(0).getString("text")
        assertEquals("orchard notes", text)
    }

    @Test
    fun embedQueryUsesQueryTaskType() = runBlocking {
        http.next = AiHttpResponse(200, embedOkBody(0.1))
        provider.embed(embedConfig, "q", EmbedKind.QUERY)
        val body = JSONObject(http.calls.single().body)
        assertEquals("RETRIEVAL_QUERY", body.getString("taskType"))
    }

    @Test
    fun embedBlankKeyFailsBeforeAnyRequest() = runBlocking {
        try {
            provider.embed(embedConfig.copy(apiKey = " "), "x", EmbedKind.DOCUMENT)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.NO_KEY, e.kind)
        }
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun embedErrorMappingAndMalformedBody() = runBlocking {
        http.next = AiHttpResponse(400, "{}")
        try {
            provider.embed(embedConfig, "x", EmbedKind.DOCUMENT)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.INVALID_KEY, e.kind)
        }
        http.next = AiHttpResponse(200, """{"embeddings":[]}""")
        try {
            provider.embed(embedConfig, "x", EmbedKind.DOCUMENT)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.BAD_RESPONSE, e.kind)
        }
        http.next = AiHttpResponse(200, """{"embedding":{"values":[]}}""")
        try {
            provider.embed(embedConfig, "x", EmbedKind.DOCUMENT)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.BAD_RESPONSE, e.kind)
        }
    }

    // ---- Batch embeddings (Search Quality Pass) ----------------

    /** A batch reply: one embedding per request in the call,
     *  each a 1-vector holding the request's index within the
     *  call, so ordering across and within calls is assertable. */
    private fun batchHandler(call: FakeAiHttpClient.Call): AiHttpResponse {
        val requests = JSONObject(call.body).getJSONArray("requests")
        val embeddings = org.json.JSONArray()
        for (i in 0 until requests.length()) {
            embeddings.put(JSONObject().put("values", org.json.JSONArray().put(i.toDouble())))
        }
        return AiHttpResponse(200, JSONObject().put("embeddings", embeddings).toString())
    }

    @Test
    fun embedAllBatchesIntoOneCallPreservingOrder() = runBlocking {
        http.handler = ::batchHandler
        val texts = (0 until 5).map { "chunk $it" }
        val vectors = provider.embedAll(embedConfig, texts, EmbedKind.DOCUMENT)
        assertEquals(5, vectors.size)
        vectors.forEachIndexed { i, v -> assertEquals(i.toFloat(), v[0]) }
        val call = http.calls.single()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/" +
                "gemini-embedding-001:batchEmbedContents",
            call.url,
        )
        assertEquals("TEST-KEY-123", call.headers["x-goog-api-key"])
        val requests = JSONObject(call.body).getJSONArray("requests")
        assertEquals(5, requests.length())
        val first = requests.getJSONObject(0)
        assertEquals("models/gemini-embedding-001", first.getString("model"))
        assertEquals("RETRIEVAL_DOCUMENT", first.getString("taskType"))
        val text = first.getJSONObject("content").getJSONArray("parts")
            .getJSONObject(0).getString("text")
        assertEquals("chunk 0", text)
    }

    @Test
    fun embedAllChunksAtOneHundred() = runBlocking {
        http.handler = ::batchHandler
        val vectors = provider.embedAll(embedConfig, (0 until 101).map { "t$it" }, EmbedKind.QUERY)
        assertEquals(101, vectors.size)
        assertEquals(2, http.calls.size)
        val sizes = http.calls.map { JSONObject(it.body).getJSONArray("requests").length() }
        assertEquals(listOf(100, 1), sizes)
        // The second call's vectors restart at its own indices.
        assertEquals(99.0f, vectors[99][0])
        assertEquals(0.0f, vectors[100][0])
        val firstRequest = JSONObject(http.calls.first().body)
            .getJSONArray("requests").getJSONObject(0)
        assertEquals("RETRIEVAL_QUERY", firstRequest.getString("taskType"))
    }

    @Test
    fun embedAllCountMismatchIsBadResponse() = runBlocking {
        // Two texts in, one embedding back: a silently misaligned
        // vector is worse than an honest failure.
        http.next = AiHttpResponse(200, """{"embeddings":[{"values":[0.5]}]}""")
        try {
            provider.embedAll(embedConfig, listOf("a", "b"), EmbedKind.DOCUMENT)
            fail("expected AiException")
        } catch (e: AiException) {
            assertEquals(AiErrorKind.BAD_RESPONSE, e.kind)
        }
    }

    @Test
    fun embedAllEmptyAndSingleSkipTheBatchEndpoint() = runBlocking {
        assertTrue(provider.embedAll(embedConfig, emptyList(), EmbedKind.DOCUMENT).isEmpty())
        assertTrue(http.calls.isEmpty())
        http.next = AiHttpResponse(200, embedOkBody(0.75))
        val single = provider.embedAll(embedConfig, listOf("only"), EmbedKind.DOCUMENT)
        assertEquals(1, single.size)
        assertEquals(0.75f, single[0][0])
        assertTrue(http.calls.single().url.endsWith(":embedContent"))
    }

    // PR #55 (Argon F-12): the production HTTP client caps the
    // response body — an over-cap body fails as the transport
    // error the AI layer already maps (AiNetworkException ->
    // OFFLINE), never as an unbounded in-memory string.
    @Test
    fun httpClient_overCapBody_failsAsTransportError() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody("x".repeat(4 * 1024 * 1024 + 1024)),
            )
            server.start()
            try {
                OkHttpAiClient().post(server.url("/v1/chat").toString(), emptyMap(), "{}")
                fail("an over-cap body must not be read into memory")
            } catch (e: AiNetworkException) {
                // expected: the same outcome as any transport failure
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun httpClient_inCapBody_roundTrips() = runBlocking {
        val server = MockWebServer()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
            server.start()
            val response = OkHttpAiClient().post(
                server.url("/v1/chat").toString(), emptyMap(), "{}",
            )
            assertEquals(200, response.status)
            assertEquals("{\"ok\":true}", response.body)
        } finally {
            server.shutdown()
        }
    }
}
