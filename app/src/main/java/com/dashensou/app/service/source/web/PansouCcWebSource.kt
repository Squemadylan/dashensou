package com.dashensou.app.service.source.web

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.service.source.SearchOutcome
import com.dashensou.app.service.source.SearchSource
import com.dashensou.app.util.CategoryRules
import com.dashensou.app.util.FileTypes
import com.dashensou.app.web.AppWebView
import com.dashensou.app.web.JsExtractors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.URLEncoder

/**
 * pansou.cc via shared WebView.
 *
 * Why this exists alongside [com.dashensou.app.service.source.PansouCcSource]:
 *   The OkHttp + Jsoup variant gets blocked by Cloudflare ("Checking your
 *   browser..." interstitial) and can't read the result list. The WebView
 *   variant executes the CF challenge transparently and reads the rendered
 *   DOM. Slower per-search (CF challenge ~3-5s on first hit, cached after)
 *   but recovers coverage for pansou.cc.
 *
 * Trade-offs vs the OkHttp variant:
 *   - perSourceTimeoutMs = 25s (vs 4.5s) — CF unlock + JS render headroom.
 *   - All three web sources share one WebView, so the SearchService fan-out
 *     effectively serializes WebView-backed calls behind [AppWebView]'s mutex.
 */
class PansouCcWebSource : SearchSource {

    override val id = "pansou_cc-web"
    override val displayName = "盘搜(浏览器)"
    override var enabled: Boolean = true
    override val perSourceTimeoutMs: Long = 25_000L

    private val baseUrl = "https://pansou.cc"

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext SearchOutcome.Success(emptyList())

        val encoded = runCatching {
            URLEncoder.encode(keyword.trim(), "UTF-8")
        }.getOrDefault(keyword.trim())
        val url = "$baseUrl/s/$encoded-$page.html"
        Log.i(TAG, "search: keyword='$keyword' page=$page url=$url")

        val raw = AppWebView.fetchAndExtract(
            url = url,
            jsExtractor = JsExtractors.pansouList(),
            timeoutMs = 18_000L,
            settleDelayMs = 500L,
        ) ?: return@withContext SearchOutcome.Failure.sourceDown("WebView 抓取失败或超时")

        try {
            val arr = JSONArray(raw)
            val results = mutableListOf<SearchResult>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val title = obj.optString("title", "")
                val href = obj.optString("href", "")
                if (title.isBlank() || href.isBlank()) continue
                val detailUrl = if (href.startsWith("http")) href else baseUrl + href
                val fileType = FileTypes.detectFromTitle(title)
                if (!CategoryRules.matchesByNetDisk(NetDiskType.OTHER, fileType, category)) continue

                results.add(
                    SearchResult(
                        id = "pansou-web-$i-${detailUrl.hashCode()}",
                        title = title,
                        description = "",
                        url = detailUrl,
                        netDiskType = NetDiskType.OTHER,
                        size = obj.optString("size", ""),
                        date = obj.optString("date", ""),
                        sourceUrl = detailUrl,
                        sourceName = displayName,
                        sourceId = id,
                        category = category,
                        fileType = fileType,
                        isValid = true,
                        requiresWebView = true,
                    )
                )
            }
            Log.i(TAG, "parsed: count=${results.size}")
            SearchOutcome.Success(results)
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}", e)
            SearchOutcome.Failure.parse("解析失败: ${e.message ?: "未知"}", e)
        }
    }

    companion object {
        private const val TAG = "PansouCcWebSource"
    }
}