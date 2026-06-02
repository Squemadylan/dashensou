package com.dashensou.app.service

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.util.NetDiskUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class SearchService {

    companion object {
        private const val TAG = "SearchService"
        private const val BASE_URL = "https://pansou.cc"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 DaShenSou/1.0"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    sealed class SearchOutcome {
        data class Success(val results: List<SearchResult>) : SearchOutcome()
        data class Failure(val message: String, val cause: Throwable? = null) : SearchOutcome()
    }

    suspend fun search(
        keyword: String,
        page: Int = 1,
        category: ResourceCategory = ResourceCategory.ALL
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

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val response = client.newCall(request).execute()
            val bodyLen = response.body?.contentLength() ?: -1L
            Log.i(TAG, "search response: code=${response.code} bodyLength=$bodyLen")

            if (!response.isSuccessful) {
                return@withContext SearchOutcome.Failure("HTTP ${response.code}")
            }
            val body = response.body
            if (body == null) {
                return@withContext SearchOutcome.Failure("响应体为空")
            }
            val html = body.string()
            val document = Jsoup.parse(html, BASE_URL)
            val results = parsePansouResults(document, category)
            Log.i(TAG, "search parsed: count=${results.size} (after category=$category filter)")
            SearchOutcome.Success(results)
        } catch (e: IOException) {
            Log.e(TAG, "search IO failed: ${e.message}", e)
            SearchOutcome.Failure("网络异常: ${e.message ?: "未知"}", e)
        } catch (e: Exception) {
            Log.e(TAG, "search failed: ${e.message}", e)
            SearchOutcome.Failure("解析失败: ${e.message ?: "未知"}", e)
        }
    }

    private fun parsePansouResults(document: Document, category: ResourceCategory): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        val items = document.select("div.resource-item-wrap")
        Log.d(TAG, "matched resource-item-wrap: ${items.size}")

        items.forEachIndexed { index, item ->
            try {
                val titleEl = item.selectFirst("h3.resource-title a") ?: return@forEachIndexed
                val title = titleEl.text()?.trim()?.let { Jsoup.parseBodyFragment(it).body().text() } ?: return@forEachIndexed
                if (title.isEmpty()) return@forEachIndexed

                val href = titleEl.attr("href")
                val detailUrl = if (href.startsWith("http")) href else BASE_URL + href

                val sizeEl = item.selectFirst(".resource-meta .em")
                val size = sizeEl?.text()?.trim() ?: ""

                val timeEl = item.selectFirst(".other-info .time")
                val date = timeEl?.text()?.trim() ?: ""

                val fileType = getFileType(title)
                if (!matchesCategory(category, fileType)) {
                    return@forEachIndexed
                }

                results.add(
                    SearchResult(
                        id = "$index-${System.currentTimeMillis()}",
                        title = title,
                        description = "",
                        url = detailUrl,
                        netDiskType = NetDiskType.OTHER,
                        size = size,
                        date = date,
                        sourceUrl = detailUrl,
                        category = category,
                        fileType = fileType,
                        isValid = true
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "parse item failed: index=$index", e)
            }
        }

        return results
    }

    suspend fun fetchDetailUrl(detailUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "fetchDetail start: $detailUrl")
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
            val html = response.body!!.string()
            val document = Jsoup.parse(html, detailUrl)
            val candidates = document.select("a[href]")
            for (a in candidates) {
                val href = a.attr("href")
                if (href.isBlank() || !href.startsWith("http")) continue
                if (href.contains("pan.baidu.com") ||
                    href.contains("pan.quark.cn") ||
                    href.contains("pan.xunlei.com") ||
                    href.contains("aliyundrive.com") ||
                    href.contains("123pan.com") ||
                    href.contains("123684.com")) {
                    Log.i(TAG, "fetchDetail hit: $href")
                    return@withContext href
                }
            }
            Log.w(TAG, "fetchDetail miss: no netdisk link")
            null
        } catch (e: Exception) {
            Log.e(TAG, "fetchDetail failed", e)
            null
        }
    }

    private fun getFileType(title: String): String? {
        val lower = title.lowercase()
        return when {
            lower.contains(".pdf") -> "pdf"
            lower.contains(".epub") -> "epub"
            lower.contains(".mobi") -> "mobi"
            lower.contains(".azw3") -> "mobi"
            lower.contains(".txt") -> "txt"
            lower.contains(".mp3") -> "mp3"
            lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".avi") || lower.contains(".rmvb") || lower.contains(".ts") -> "video"
            lower.contains(".zip") || lower.contains(".rar") || lower.contains(".7z") -> "archive"
            else -> null
        }
    }

    private fun matchesCategory(category: ResourceCategory, fileType: String?): Boolean {
        if (category == ResourceCategory.ALL) return true
        return when (category) {
            ResourceCategory.EBOOK -> fileType in listOf("pdf", "epub", "mobi", "txt")
            ResourceCategory.MOVIE -> fileType == "video"
            ResourceCategory.TV -> fileType == "video"
            else -> true
        }
    }
}
