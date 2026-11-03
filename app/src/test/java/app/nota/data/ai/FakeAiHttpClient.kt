package app.nota.data.ai

/** Shared fake transport for the AI provider/service tests:
 *  records every request, replays one canned response (or throws
 *  one canned failure). No real network anywhere in the suite. */
class FakeAiHttpClient : AiHttpClient {
    data class Call(val url: String, val headers: Map<String, String>, val body: String)

    val calls = mutableListOf<Call>()
    var next: AiHttpResponse = AiHttpResponse(200, "{}")
    var failure: Throwable? = null

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
    ): AiHttpResponse {
        calls += Call(url, headers, jsonBody)
        failure?.let { throw it }
        return next
    }
}
