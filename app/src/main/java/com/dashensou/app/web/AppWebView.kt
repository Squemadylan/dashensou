package com.dashensou.app.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide singleton WebView used by HTML-anti-scraping sources
 * (pansou_cc / haisou / aiqu225). Multiple [com.dashensou.app.service.source.SearchSource]
 * implementations share this single instance; concurrency is serialized via
 * a [Mutex] so only one page is loaded at a time.
 *
 * Threading model:
 *   - Public suspend functions may be called from any dispatcher.
 *   - Internally hops to [Dispatchers.Main] because the Android WebView API
 *     is main-thread-only. All WebView mutations and callbacks stay there.
 *
 * Lifecycle:
 *   - [init] must be called from `App.onCreate()` (before any search runs).
 *   - The instance lives for the entire process; reused across Activity
 *     recreation. Memory footprint is ~50 MB long-lived.
 *   - [shutdown] is opt-in (tests / process teardown).
 */
object AppWebView {

    private const val TAG = "AppWebView"
    private const val DEFAULT_TIMEOUT_MS = 30_000L
    private const val DEFAULT_SETTLE_MS = 300L

    /** Modern Chrome UA — some CF challenges drop UAs older than Chrome 100. */
    private const val MODERN_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private var wv: WebView? = null
    private val mutex = Mutex()
    private val reqCounter = AtomicInteger(0)

    // Concurrency: the [Mutex] above already serializes everything that
    // mutates `wv`, but the [evaluateJavascript] callback fires on the
    // main thread and may run after we've moved on (e.g. after timeout).
    // We use a ConcurrentHashMap to safely remove entries from any context.
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String?>>()

    fun init(context: Context) {
        if (wv != null) return
        val view = WebView(context.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = MODERN_UA
                loadWithOverviewMode = true
                useWideViewPort = true
            }
        }
        wv = view
        Log.i(TAG, "initialized (modern UA, JS+DOM storage enabled, no cache)")
    }

    /**
     * Load [url] in the shared WebView, wait for page-load completion,
     * then evaluate [jsExtractor] and return its raw result.
     *
     * @param jsExtractor A JS expression whose value (string) will be
     *   returned. Conventionally ends with `JSON.stringify(items)`.
     * @param settleDelayMs Extra wait after `onPageFinished` before
     *   evaluating JS — needed for SPA sites (Vue/React) that finish
     *   the network request but haven't yet rendered the result list.
     * @return The raw JS result string (e.g. `'[{"title":"..."}]'`) or
     *   `null` on timeout / load error / not initialized.
     */
    suspend fun fetchAndExtract(
        url: String,
        jsExtractor: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        settleDelayMs: Long = DEFAULT_SETTLE_MS,
    ): String? = withContext(Dispatchers.Main) {
        val view = wv ?: run {
            Log.w(TAG, "fetchAndExtract: not initialized")
            return@withContext null
        }
        mutex.withLock {
            val reqId = reqCounter.incrementAndGet()
            val deferred = CompletableDeferred<String?>()
            pending[reqId] = deferred

            val client = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String) {
                    super.onPageFinished(view, u)
                    // Guard: timeout may have already removed reqId.
                    if (!pending.containsKey(reqId)) return
                    if (settleDelayMs <= 0) {
                        runExtract(reqId, view, jsExtractor)
                    } else {
                        Handler(Looper.getMainLooper()).postDelayed({
                            runExtract(reqId, view, jsExtractor)
                        }, settleDelayMs)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // Only main-frame errors are fatal; sub-resource failures
                    // (CSS/IMG) shouldn't abort the fetch.
                    if (request.isForMainFrame && pending.containsKey(reqId)) {
                        Log.w(TAG, "main frame error for ${request.url}: ${error.description}")
                        pending.remove(reqId)?.complete(null)
                    }
                }
            }
            view.webViewClient = client
            Log.i(TAG, "load reqId=$reqId url=$url")
            view.loadUrl(url)

            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result == null) {
                pending.remove(reqId)
                Log.w(TAG, "timeout reqId=$reqId url=$url budget=${timeoutMs}ms")
            } else {
                Log.i(TAG, "done reqId=$reqId resultLen=${result.length}")
            }
            result
        }
    }

    private fun runExtract(reqId: Int, view: WebView, js: String) {
        if (!pending.containsKey(reqId)) return
        view.evaluateJavascript(js) { raw ->
            pending.remove(reqId)?.complete(raw)
        }
    }

    /** Tear down. Not auto-invoked — kept for tests / `App.onTerminate`. */
    fun shutdown() {
        runCatching {
            wv?.stopLoading()
            wv?.loadUrl("about:blank")
            wv?.destroy()
        }
        wv = null
        pending.clear()
        Log.i(TAG, "shutdown")
    }
}