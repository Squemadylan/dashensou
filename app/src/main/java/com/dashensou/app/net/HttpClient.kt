package com.dashensou.app.net

import android.util.Log
import com.dashensou.app.util.Json
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

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
     * failure (network, non-2xx, empty body, cancellation propagates).
     *
     * Why: source code used to repeat the same `client.newCall(req)
     * .execute().use { ... }` block 4-5 times per file, with subtly
     * different log messages and error classification. Centralising
     * here lets us own the user-facing error message + log line in
     * one place.
     *
     * [charset] is a Charset name (e.g. "GBK"); default UTF-8. A few
     * older Chinese sites (aiqu225) declare GBK even on their 200
     * response — pass "GBK" for those. If the charset name itself is
     * invalid we fall back to UTF-8 and log a warning, rather than
     * throwing IllegalArgumentException out of the suspend boundary.
     */
    suspend fun getString(
        url: String,
        userAgent: String = DEFAULT_UA,
        perCallTimeoutMs: Long? = null,
        charset: String = "UTF-8"
    ): String? = withContext(Dispatchers.IO) {
        val request = newGet(url, userAgent)
        val call = if (perCallTimeoutMs != null) {
            newCallWithTimeout(request, perCallTimeoutMs)
        } else {
            client.newCall(request)
        }
        val resolvedCharset = try {
            java.nio.charset.Charset.forName(charset)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "invalid charset '$charset', falling back to UTF-8")
            java.nio.charset.Charset.forName("UTF-8")
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "GET $url -> HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body
                if (body == null) {
                    Log.w(TAG, "GET $url -> empty body")
                    return@withContext null
                }
                // body.string() uses the Charset from the media type
                // header, which 90% of sites never send correctly. Read
                // as bytes and decode locally with the caller's charset.
                val bytes = body.bytes()
                if (bytes.isEmpty()) return@withContext null
                String(bytes, resolvedCharset)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "GET $url failed: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "GET $url unexpected: ${e.message}", e)
            null
        }
    }

    /**
     * GET [url] and parse the response as JSON. Returns null if the
     * request or the parse fails. Cancellation propagates.
     *
     * Convenience for the JSON-API sources (万站API / 52api / pansou252
     * / pansou / pansou.cc / openlibrary / gutendex) which used to
     * open-code the same `JSONObject(body)` try/catch dance.
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

    private fun newCallWithTimeout(request: Request, ms: Long): okhttp3.Call {
        // Per-call client copy: align read/write/call so a 15s coroutine
        // budget isn't cut short by the shared 12s read timeout.
        val copy = HttpClient.client.newBuilder()
            .readTimeout(ms, TimeUnit.MILLISECONDS)
            .writeTimeout(ms, TimeUnit.MILLISECONDS)
            .callTimeout(ms, TimeUnit.MILLISECONDS)
            .build()
        return copy.newCall(request)
    }
}
