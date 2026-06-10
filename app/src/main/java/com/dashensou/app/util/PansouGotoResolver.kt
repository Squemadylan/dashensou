package com.dashensou.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * pansou.cc `/goto/<hash>.html` pages decrypt the real net-disk share URL
 * in obfuscated JavaScript (RSA + redirect). A plain HTTP GET only sees
 * the encrypted blob — the actual pan.baidu.com / pan.quark.cn URL is
 * produced client-side. We spin up a short-lived, headless WebView on
 * the main thread, wait for the redirect to a known net-disk host, then
 * tear it down.
 */
object PansouGotoResolver {

    private val NET_DISK_DOMAINS = listOf(
        "pan.baidu.com",
        "yun.baidu.com",
        "pan.quark.cn",
        "drive.quark.cn",
        "pan.xunlei.com",
        "aliyundrive.com",
        "alipan.com",
        "123pan.com",
        "123684.com"
    )

    suspend fun resolve(
        context: Context,
        gotoUrl: String,
        timeoutMs: Long = 10_000L
    ): String? = withContext(Dispatchers.Main) {
        if (gotoUrl.isBlank()) return@withContext null
        if (NET_DISK_DOMAINS.any { gotoUrl.contains(it, ignoreCase = true) }) {
            return@withContext gotoUrl
        }
        suspendCancellableCoroutine { cont ->
            val appCtx = context.applicationContext
            val webView = WebView(appCtx)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString =
                webView.settings.userAgentString + " DaShenSou/1.0"

            val handler = Handler(Looper.getMainLooper())
            var finished = false
            val timeoutRunnable = Runnable {
                if (finished) return@Runnable
                finished = true
                destroyWebView(webView)
                if (cont.isActive) cont.resume(null)
            }

            fun complete(url: String?) {
                if (finished) return
                val resolved = url?.takeIf {
                    NET_DISK_DOMAINS.any { host -> it.contains(host, ignoreCase = true) }
                }
                if (resolved == null) return
                finished = true
                handler.removeCallbacks(timeoutRunnable)
                destroyWebView(webView)
                if (cont.isActive) cont.resume(resolved)
            }

            handler.postDelayed(timeoutRunnable, timeoutMs)

            cont.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                destroyWebView(webView)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString() ?: return false
                    if (NET_DISK_DOMAINS.any { url.contains(it, ignoreCase = true) }) {
                        complete(url)
                        return true
                    }
                    return false
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (url.isNullOrBlank()) return false
                    if (NET_DISK_DOMAINS.any { url.contains(it, ignoreCase = true) }) {
                        complete(url)
                        return true
                    }
                    return false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (!url.isNullOrBlank()) {
                        complete(url)
                    }
                }
            }
            webView.loadUrl(gotoUrl)
        }
    }

    private fun destroyWebView(webView: WebView) {
        runCatching {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }
}
