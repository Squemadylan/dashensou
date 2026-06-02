package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class GutendexSource(
    private val client: OkHttpClient = defaultClient()
) : SearchSource {

    override val id = "gutendex"
    override val displayName = "Gutendex (公版书)"
    override var enabled: Boolean = true

    companion object {
        private const val TAG = "GutendexSource"
        private const val BASE_URL = "https://gutendex.com/books"
        private const val USER_AGENT = "DaShenSou/1.0 (Android)"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (category != ResourceCategory.ALL && category != ResourceCategory.EBOOK) {
            return@withContext SearchOutcome.Success(emptyList())
        }
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }

        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("search", keyword.trim())
            .addQueryParameter("page", page.toString())
            .build()
            .toString()
        Log.i(TAG, "search: keyword='$keyword' page=$page url=$url")

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
            val body = response.body?.string()
                ?: return@withContext SearchOutcome.Failure("响应体为空")
            val root = JSONObject(body)
            val resultsArr = root.optJSONArray("results")
                ?: return@withContext SearchOutcome.Success(emptyList())

            val results = mutableListOf<SearchResult>()
            val len = resultsArr.length()
            for (i in 0 until len) {
                val book = resultsArr.optJSONObject(i)
                if (book == null) continue
                val title = book.optString("title", "")
                if (title.isBlank()) continue
                val id = book.optInt("id", 0)
                if (id == 0) continue
                val authors = book.optJSONArray("authors")
                val author = if (authors != null && authors.length() > 0) {
                    authors.optJSONObject(0)?.optString("name") ?: "未知作者"
                } else {
                    "未知作者"
                }
                val downloadCount = book.optInt("download_count", 0)
                val formats = book.optJSONObject("formats") ?: JSONObject()
                val downloadUrl = pickDownloadUrl(formats)

                if (downloadUrl.isBlank()) continue

                val desc = StringBuilder()
                desc.append(author)
                desc.append(" · 📖 公版免费")
                if (downloadCount > 0) desc.append(" · 下载 $downloadCount 次")

                results.add(
                    SearchResult(
                        id = "gutendex-$id",
                        title = title,
                        description = desc.toString(),
                        url = downloadUrl,
                        netDiskType = NetDiskType.DIRECT_URL,
                        size = "",
                        date = "",
                        sourceUrl = "https://www.gutenberg.org/ebooks/$id",
                        sourceName = displayName,
                        category = ResourceCategory.EBOOK,
                        fileType = detectFileType(downloadUrl),
                        isValid = true,
                        requiresWebView = false,
                        extractionCode = null
                    )
                )
            }
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

    private fun pickDownloadUrl(formats: JSONObject): String {
        val preferred = listOf(
            "application/epub+zip",
            "application/epub",
            "text/html; charset=utf-8",
            "text/html",
            "text/plain; charset=utf-8",
            "text/plain",
            "application/pdf"
        )
        for (mime in preferred) {
            val url = formats.optString(mime, "")
            if (url.isNotBlank()) return url
        }
        val keys = formats.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = formats.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun detectFileType(url: String): String = when {
        url.contains(".epub", ignoreCase = true) -> "epub"
        url.contains(".pdf", ignoreCase = true) -> "pdf"
        url.contains(".txt", ignoreCase = true) -> "txt"
        url.contains("text/plain", ignoreCase = true) -> "txt"
        url.contains("text/html", ignoreCase = true) -> "html"
        else -> "ebook"
    }
}
