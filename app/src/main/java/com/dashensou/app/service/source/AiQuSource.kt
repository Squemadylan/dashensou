package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

class AiQuSource(
    private val client: OkHttpClient = defaultClient()
) : SearchSource {

    override val id = "aiqu225"
    override val displayName = "aiqu225 (小说)"
    override var enabled: Boolean = true

    companion object {
        private const val TAG = "AiQuSource"
        private const val BASE_URL = "https://www.aiqu225.com"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 DaShenSou/1.0"
        private val GBK = Charset.forName("GBK")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    data class DetailInfo(
        val netDiskType: NetDiskType,
        val password: String?,
        val downloadUrl: String
    )

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }
        val encoded = try {
            URLEncoder.encode(keyword.trim(), "GBK")
        } catch (e: Exception) {
            keyword.trim()
        }
        val url = "$BASE_URL/search.asp?word=$encoded"
        Log.i(TAG, "search: keyword='$keyword' url=$url")

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext SearchOutcome.Failure("HTTP ${response.code}")
            }
            val bytes = response.body?.bytes()
                ?: return@withContext SearchOutcome.Failure("响应体为空")
            val html = String(bytes, GBK)
            val document = Jsoup.parse(html, BASE_URL)
            val results = parseSearchResults(document)
            Log.i(TAG, "parsed: count=${results.size}")
            SearchOutcome.Success(results)
        } catch (e: IOException) {
            Log.e(TAG, "IO failed: ${e.message}", e)
            SearchOutcome.Failure("网络异常: ${e.message ?: "未知"}", e)
        } catch (e: Exception) {
            Log.e(TAG, "failed: ${e.message}", e)
            SearchOutcome.Failure("解析失败: ${e.message ?: "未知"}", e)
        }
    }

    suspend fun fetchDetail(detailUrl: String): DetailInfo? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "fetchDetail: $detailUrl")
            val request = Request.Builder()
                .url(detailUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful || response.body == null) {
                Log.w(TAG, "fetchDetail HTTP ${response.code}")
                return@withContext null
            }
            val bytes = response.body!!.bytes()
            val html = String(bytes, GBK)
            val document = Jsoup.parse(html, detailUrl)

            val candidates = document.select("a[href]")
            for (a in candidates) {
                val href = a.attr("href")
                if (href.isBlank()) continue
                val lower = href.lowercase()
                if (lower.endsWith(".txt") || lower.contains(".txt?") || lower.contains("/down/")) {
                    val full = if (href.startsWith("http")) href else BASE_URL + href
                    val pwd = extractPassword(document)
                    Log.i(TAG, "fetchDetail hit: $full pwd=$pwd")
                    return@withContext DetailInfo(NetDiskType.DIRECT_URL, pwd, full)
                }
            }
            val zipCandidates = document.select("a[href$=.zip], a[href$=.rar]")
            for (a in zipCandidates) {
                val href = a.attr("href")
                if (href.isBlank()) continue
                val full = if (href.startsWith("http")) href else BASE_URL + href
                val pwd = extractPassword(document)
                Log.i(TAG, "fetchDetail hit (zip): $full pwd=$pwd")
                return@withContext DetailInfo(NetDiskType.DIRECT_URL, pwd, full)
            }
            Log.w(TAG, "fetchDetail: no .txt/.zip link found")
            null
        } catch (e: Exception) {
            Log.e(TAG, "fetchDetail failed", e)
            null
        }
    }

    private fun extractPassword(doc: Document): String? {
        val body = doc.body().text()
        val match = Regex("密码[：:\\s]*([A-Za-z0-9]{4,8})").find(body) ?: return null
        return match.groupValues[1]
    }

    private fun parseSearchResults(document: Document): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val cards = document.select("div.search-card")
        Log.d(TAG, "matched search-card: ${cards.size}")

        for (card in cards) {
            try {
                val titleEl = card.selectFirst("a.searchtitle") ?: continue
                val title = titleEl.text().trim().let { Jsoup.parseBodyFragment(it).body().text() }
                if (title.isEmpty()) continue
                val href = titleEl.attr("href")
                val detailUrl = if (href.startsWith("http")) href else BASE_URL + href
                val author = card.selectFirst(".search-card-author")?.text()
                    ?.replace("作者：", "")?.replace("作者:", "")?.trim() ?: ""
                val cat = card.selectFirst(".search-card-category a")?.text()?.trim()
                    ?: card.selectFirst(".search-card-category")?.text()?.trim() ?: ""
                val date = card.selectFirst(".oldDate")?.text()?.trim()
                    ?: card.selectFirst(".search-card-date")?.text()?.trim() ?: ""
                val content = card.selectFirst(".search-card-content")?.text()?.trim() ?: ""

                val desc = StringBuilder()
                if (author.isNotEmpty()) desc.append("作者：$author")
                if (cat.isNotEmpty()) desc.append(" · $cat")
                if (content.isNotEmpty()) {
                    val snippet = if (content.length > 80) content.substring(0, 80) + "..." else content
                    desc.append(" · $snippet")
                }

                results.add(
                    SearchResult(
                        id = "aiqu-${detailUrl.hashCode()}",
                        title = title,
                        description = desc.toString(),
                        url = detailUrl,
                        netDiskType = NetDiskType.OTHER,
                        size = "",
                        date = date,
                        sourceUrl = detailUrl,
                        sourceName = displayName,
                        category = ResourceCategory.EBOOK,
                        fileType = "txt",
                        isValid = true,
                        requiresWebView = true,
                        extractionCode = null
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "parse card failed", e)
            }
        }

        return results
    }
}
