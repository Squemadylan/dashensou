package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import com.dashensou.app.util.CategoryRules
import com.dashensou.app.util.FileTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

class PansouCcSource : SearchSource {

    override val id = "pansou_cc"
    override val displayName = "搜盘来源"
    override var enabled: Boolean = true
    // pansou.cc 列表页也是慢站,实测 1.8-2.5s 起步。给 4.5s 预算。
    override val perSourceTimeoutMs: Long = 4_500L

    companion object {
        private const val TAG = "PansouCcSource"
        private const val BASE_URL = "https://pansou.cc"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 DaShenSou/1.0"
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }
        val encoded = try {
            URLEncoder.encode(keyword.trim(), "UTF-8")
        } catch (e: Exception) {
            keyword.trim()
        }
        val url = "$BASE_URL/s/$encoded-$page.html"
        Log.i(TAG, "search start: keyword='$keyword' page=$page url=$url")

        val html = HttpClient.getString(url, userAgent = USER_AGENT)
        if (html == null) {
            return@withContext SearchOutcome.Failure.sourceDown("响应为空或网络异常")
        }
        try {
            val document = Jsoup.parse(html, BASE_URL)
            val results = parsePansouResults(document, category)
            Log.i(TAG, "search parsed: count=${results.size}")
            SearchOutcome.Success(results)
        } catch (e: Exception) {
            Log.e(TAG, "parse failed: ${e.message}", e)
            SearchOutcome.Failure.parse("解析失败: ${e.message ?: "未知"}", e)
        }
    }

    data class DetailInfo(
        val netDiskType: NetDiskType,
        val password: String?,
        val gotoUrl: String
    )

    suspend fun fetchDetail(detailUrl: String): DetailInfo? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "fetchDetail start: $detailUrl")
            val html = HttpClient.getString(detailUrl, userAgent = USER_AGENT)
                ?: run {
                    Log.w(TAG, "fetchDetail: empty body")
                    return@withContext null
                }
            val document = Jsoup.parse(html, detailUrl)

            val password = extractPassword(document)

            val gotoBtn = document.select("a.button[href^=/goto/]").firstOrNull()
                ?: document.select("a[href^=/goto/]").firstOrNull()
            val gotoHref = gotoBtn?.attr("href")
            if (gotoHref.isNullOrBlank()) {
                Log.w(TAG, "fetchDetail: no /goto/ link found in detail page")
                return@withContext null
            }
            val gotoUrl = if (gotoHref.startsWith("http")) gotoHref else BASE_URL + gotoHref

            val netDiskType = parseNetDiskType(gotoBtn.text(), document)

            Log.i(TAG, "fetchDetail hit: type=$netDiskType password=$password gotoUrl=$gotoUrl")
            DetailInfo(netDiskType, password, gotoUrl)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDetail failed", e)
            null
        }
    }

    private fun parseNetDiskType(buttonText: String, document: org.jsoup.nodes.Document): NetDiskType {
        val source = buttonText + " " + (document.title() ?: "")
        return when {
            source.contains("百度") || source.contains("baidu") -> NetDiskType.BAIDU
            source.contains("夸克") || source.contains("quark") -> NetDiskType.QUARK
            source.contains("迅雷") || source.contains("xunlei") -> NetDiskType.XUNLEI
            source.contains("阿里") || source.contains("aliyun") || source.contains("ali") -> NetDiskType.ALIYUN
            source.contains("123") -> NetDiskType.YUNPAN123
            else -> NetDiskType.OTHER
        }
    }

    /**
     * pansou.cc puts the extraction code in `#pwd` on the detail page
     * (not on the search list). Guard against picking up UI chrome like
     * "点击复制".
     */
    private fun extractPassword(document: org.jsoup.nodes.Document): String? {
        val candidates = listOf(
            document.select("#pwd").firstOrNull()?.text(),
            document.select(".resource-meta #pwd").firstOrNull()?.text(),
            document.select(".copy-item #pwd").firstOrNull()?.text()
        )
        for (raw in candidates) {
            val v = raw?.trim().orEmpty()
            if (v.isBlank()) continue
            if (v.equals("点击复制", ignoreCase = true)) continue
            if (v.length > 20) continue
            return v
        }
        val html = document.html()
        val m = Regex("""提取密码\s*</span>\s*<span[^>]*id="pwd"[^>]*>([^<]+)</span>""").find(html)
        return m?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && !it.equals("点击复制", true) }
    }

    private fun parsePansouResults(
        document: org.jsoup.nodes.Document,
        category: ResourceCategory
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val items = document.select("div.resource-item-wrap")
        Log.d(TAG, "matched resource-item-wrap: ${items.size}")

        items.forEachIndexed { index, item ->
            try {
                val titleEl = item.selectFirst("h3.resource-title a") ?: return@forEachIndexed
                val title = titleEl.text().trim().let { Jsoup.parseBodyFragment(it).body().text() }
                if (title.isEmpty()) return@forEachIndexed

                val href = titleEl.attr("href")
                val detailUrl = if (href.startsWith("http")) href else BASE_URL + href

                val sizeEl = item.selectFirst(".resource-meta .em")
                val size = sizeEl?.text()?.trim() ?: ""

                val timeEl = item.selectFirst(".other-info .time")
                val date = timeEl?.text()?.trim() ?: ""

                val fileType = FileTypes.detectFromTitle(title)
                if (!CategoryRules.matchesByNetDisk(NetDiskType.OTHER, fileType, category)) {
                    return@forEachIndexed
                }

                results.add(
                    SearchResult(
                        id = "pansou-$index-${detailUrl.hashCode()}",
                        title = title,
                        description = "",
                        url = detailUrl,
                        netDiskType = NetDiskType.OTHER,
                        size = size,
                        date = date,
                        sourceUrl = detailUrl,
                        sourceName = displayName,
                        sourceId = id,
                        category = category,
                        fileType = fileType,
                        isValid = true,
                        requiresWebView = true
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "parse item failed: index=$index", e)
            }
        }

        return results
    }
}
