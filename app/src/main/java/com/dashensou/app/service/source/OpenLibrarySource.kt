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

class OpenLibrarySource(
    private val client: OkHttpClient = defaultClient()
) : SearchSource {

    override val id = "openlibrary"
    override val displayName = "Open Library (元数据)"
    override var enabled: Boolean = true

    companion object {
        private const val TAG = "OpenLibrarySource"
        private const val BASE_URL = "https://openlibrary.org/search.json"
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
            .addQueryParameter("q", keyword.trim())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "20")
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
            val docs = root.optJSONArray("docs")
                ?: return@withContext SearchOutcome.Success(emptyList())

            val results = mutableListOf<SearchResult>()
            val len = docs.length()
            for (i in 0 until len) {
                val doc = docs.optJSONObject(i)
                if (doc == null) continue
                val title = doc.optString("title", "")
                if (title.isBlank()) continue
                val authorsArr = doc.optJSONArray("author_name")
                val author = if (authorsArr != null && authorsArr.length() > 0) {
                    authorsArr.optString(0)
                } else {
                    "未知作者"
                }
                val year = doc.optInt("first_publish_year", 0)
                val editionCount = doc.optInt("edition_count", 0)
                val workKey = doc.optJSONArray("key")?.optString(0) ?: ""
                val detailUrl = if (workKey.startsWith("/")) "https://openlibrary.org$workKey" else "https://openlibrary.org/works/$workKey"
                val iaIds = doc.optJSONArray("ia")
                val hasFullText = iaIds != null && iaIds.length() > 0
                val iaUrl = if (hasFullText) "https://archive.org/details/${iaIds!!.optString(0)}" else ""

                val desc = StringBuilder()
                desc.append(author)
                if (year > 0) desc.append(" · $year 年")
                desc.append(" · $editionCount 版")
                if (hasFullText) desc.append(" · 📖 可在线阅读")

                results.add(
                    SearchResult(
                        id = "openlibrary-$i-${detailUrl.hashCode()}",
                        title = title,
                        description = desc.toString(),
                        url = if (hasFullText) iaUrl else detailUrl,
                        netDiskType = NetDiskType.DIRECT_URL,
                        size = "",
                        date = if (year > 0) year.toString() else "",
                        sourceUrl = detailUrl,
                        sourceName = displayName,
                        category = ResourceCategory.EBOOK,
                        fileType = "ebook",
                        isValid = true,
                        requiresWebView = true,
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
}
