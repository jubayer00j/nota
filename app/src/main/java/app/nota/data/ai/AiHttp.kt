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
    private val client: OkHttpClient = OkHttpClient(),
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
                val body = try {
                    response.body.string()
                } catch (e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(AiNetworkException(e))
                    return
                }
                if (!cont.isCancelled) {
                    cont.resume(AiHttpResponse(response.code, body))
                }
            }
        })
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=UTF-8".toMediaType()
    }
}
