package app.nota.data.ai

import kotlinx.coroutines.runBlocking
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
    private val config = AiConfig(AiProviderId.GEMINI, apiKey = "<redacted>", model = "")

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
}
