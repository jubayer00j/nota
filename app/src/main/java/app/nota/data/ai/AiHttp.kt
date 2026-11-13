package app.nota.data.ai

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A completed HTTP exchange: status + raw body text. */
data class AiHttpResponse(val status: Int, val body: String)

/** Thrown by [AiHttpClient] implementations for transport-level
 *  failures (DNS, timeout, no route) — providers map it to
 *  [AiErrorKind.OFFLINE]. Never carries request data. */
class AiNetworkException(cause: Throwable) : Exception(cause)

/**
 * The AI layer's HTTP seam (Phase 4.1). Providers depend only on
 * this interface, so every provider behaviour — request shape,
 * response parsing, error mapping — is testable against fakes with
 * zero real network, and CI never touches a live endpoint.
 */
interface AiHttpClient {
    /** POSTs [jsonBody] to [url] with [headers]; returns the raw
     *  response for ANY status (error mapping is the provider's
     *  job). Throws [AiNetworkException] on transport failure and
     *  [kotlinx.coroutines.CancellationException] when the calling
     *  coroutine is cancelled. */
    suspend fun post(url: String, headers: Map<String, String>, jsonBody: String): AiHttpResponse
}

/** Production client: a plain OkHttp POST (the Drive precedent —
 *  DriveClient takes its OkHttpClient the same injectable way).
 *  The call is enqueued so coroutine cancellation cancels the
 *  in-flight request, which is what the editor's Cancel button
 *  relies on. */
class OkHttpAiClient(
    private val client: OkHttpClient = defaultClient(),
) : AiHttpClient {

    override suspend fun post(
        url: String,
        headers: Map<String, String>,
        jsonBody: String,
    ): AiHttpResponse = suspendCancellableCoroutine { cont ->
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .post(jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                // A cancel surfaces here as an IOException too; the
                // coroutine is already cancelled in that case, so a
                // resume attempt would be dropped — but guard anyway
                // so a real failure still reports OFFLINE.
                cont.resumeWithException(AiNetworkException(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val body = try {
                    response.use { readBodyCapped(it) }
                } catch (e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(AiNetworkException(e))
                    return
                }
                if (!cont.isCancelled) {
                    cont.resume(AiHttpResponse(code, body))
                }
            }
        })
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()

        /** Hard cap on a response body (PR #55, Argon F-12). An AI
         *  endpoint is user-configured and may be buggy or hostile;
         *  [okhttp3.ResponseBody.string] would buffer an unbounded
         *  body whole into memory. 4 MB is far above any legitimate
         *  completion or embedding payload. */
        const val MAX_BODY_BYTES = 4L * 1024 * 1024

        /** Streams [response]'s body under [MAX_BODY_BYTES];
         *  exceeding the cap aborts with an [IOException], which
         *  the caller maps to the same transport-failure outcome
         *  ([AiNetworkException]) as any other read error — the
         *  error outcome the AI layer already understands. Bodies
         *  are JSON, decoded as UTF-8 (what the providers emit). */
        fun readBodyCapped(response: Response): String {
            val body = response.body
            if (body.contentLength() > MAX_BODY_BYTES) {
                throw IOException("AI response body exceeds the $MAX_BODY_BYTES-byte cap")
            }
            val source = body.source()
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0L
            while (true) {
                val read = source.read(chunk)
                if (read == -1) break
                total += read
                if (total > MAX_BODY_BYTES) {
                    throw IOException("AI response body exceeds the $MAX_BODY_BYTES-byte cap")
                }
                out.write(chunk, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        }

        /** Timeouts sized for generation, not pings (Codex review,
         *  Phase 4.1): OkHttp's defaults (10s read) would abort a
         *  legitimate Summarize on a long note — and the timeout
         *  would then be misreported as "offline". 60s read / 90s
         *  whole-call lets slow generations finish while real
         *  connection failures still surface quickly. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }
}
