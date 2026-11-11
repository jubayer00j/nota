package app.nota.data.ai

/** Shared fake transport for the AI provider/service tests:
 *  records every request, replays one canned response (or throws
 *  one canned failure). No real network anywhere in the suite. */
class FakeAiHttpClient : AiHttpClient {
    data class Call(val url: String, val headers: Map<String, String>, val body: String)

    val calls = mutableListOf<Call>()
    var next: AiHttpResponse = AiHttpResponse(200, "{}")
    var failure: Throwable? = null

    /** Optional per-call responder (Search Quality Pass): when
     *  set it answers each call from the recorded request —
     *  the batch-embedding tests need replies whose size follows
     *  the request. Takes precedence over [next]. */
    var handler: ((Call) -> AiHttpResponse)? = null

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
    ): AiHttpResponse {
        val call = Call(url, headers, jsonBody)
        calls += call
        failure?.let { throw it }
        handler?.let { return it(call) }
        return next
    }
}
