package com.dashensou.app.net

import android.util.Log
import com.dashensou.app.util.Json
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Shared HTTP plumbing for every search source.
 *
 * Why one client: previous version of every source had its own
 * `OkHttpClient.Builder().connectTimeout(...).readTimeout(...).build()`,
 * which means:
 *  - one connection pool / one thread pool per source (slower under
 *    fan-out)
 *  - one retry policy per source
 *  - one place to add global config (cache, interceptors, logging)
 *
 * [client] is intentionally `var` rather than `val` only so unit
 * tests can swap it out for a mock. In production code treat it as
 * immutable — every source gets the same instance.
 */
object HttpClient {

    private const val TAG = "HttpClient"

    @Volatile
    var client: OkHttpClient = defaultClient()
        private set

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Visible for tests; do not call from production code. */
    fun setForTesting(c: OkHttpClient) {
        client = c
    }

    /**
     * Build a GET request with the project's standard user agent. Kept
     * as a separate function so sources don't have to copy-paste
     * `header("User-Agent", ...)`.
     */
    fun newGet(url: String, userAgent: String = DEFAULT_UA): Request =
        Request.Builder().url(url).header("User-Agent", userAgent).get().build()

    const val DEFAULT_UA: String = "DaShenSou/1.0 (Android)"

    /**
     * GET [url] and return the body as a String. Returns null on any
     * failure (network, non-2xx, empty body). [CancellationException]
     * propagates; the underlying [Call] is cancelled on coroutine cancel.
     */
    suspend fun getString(
        url: String,
        userAgent: String = DEFAULT_UA,
        perCallTimeoutMs: Long? = null,
        charset: String = "UTF-8"
    ): String? = withContext(Dispatchers.IO) {
        val request = newGet(url, userAgent)
        val response = execute(request, perCallTimeoutMs) ?: return@withContext null
        val resolvedCharset = try {
            java.nio.charset.Charset.forName(charset)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "invalid charset '$charset', falling back to UTF-8")
            java.nio.charset.Charset.forName("UTF-8")
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "GET $url -> HTTP ${resp.code}")
                return@withContext null
            }
            val body = resp.body
            if (body == null) {
                Log.w(TAG, "GET $url -> empty body")
                return@withContext null
            }
            val bytes = body.bytes()
            if (bytes.isEmpty()) return@withContext null
            String(bytes, resolvedCharset)
        }
    }

    /**
     * GET [url] and parse the response as JSON. Returns null if the
     * request or the parse fails. Cancellation propagates.
     */
    suspend fun getJson(
        url: String,
        userAgent: String = DEFAULT_UA,
        charset: String = "UTF-8",
        perCallTimeoutMs: Long? = null
    ): JSONObject? {
        val body = getString(url, userAgent, perCallTimeoutMs, charset) ?: return null
        return try {
            Json.parseObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "GET $url -> JSON parse failed: ${e.message}")
            null
        }
    }

    /**
     * Execute [request] on the shared client. Returns null on I/O
     * failure; throws [CancellationException] when the coroutine is
     * cancelled (and cancels the OkHttp call).
     */
    suspend fun execute(request: Request, perCallTimeoutMs: Long? = null): Response? =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call = newCall(request, perCallTimeoutMs)
                cont.invokeOnCancellation { call.cancel() }
                try {
                    cont.resume(call.execute())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    if (cont.isCancelled) throw CancellationException("cancelled", e)
                    Log.w(TAG, "execute ${request.url} failed: ${e.message}")
                    cont.resume(null)
                } catch (e: Exception) {
                    Log.w(TAG, "execute ${request.url} unexpected: ${e.message}", e)
                    cont.resume(null)
                }
            }
        }

    private fun newCall(request: Request, perCallTimeoutMs: Long?): Call {
        val call = client.newCall(request)
        if (perCallTimeoutMs != null) {
            call.timeout().timeout(perCallTimeoutMs, TimeUnit.MILLISECONDS)
        }
        return call
    }
}
