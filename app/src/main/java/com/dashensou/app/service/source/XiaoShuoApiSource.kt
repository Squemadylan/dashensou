package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import com.dashensou.app.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class XiaoShuoApiSource : SearchSource {

    override val id = "xiaoshuo"
    override val displayName = "电子书直链"
    override var enabled: Boolean = false
    // axdzs API routinely takes 6-12s; default 2.5s budget always times out.
    override val perSourceTimeoutMs: Long = API_BUDGET_MS

    companion object {
        private const val TAG = "XiaoShuoApiSource"
        private const val BASE_URL = "https://api.xcvts.cn/api/xiaoshuo/axdzs"
        private const val API_BUDGET_MS = 15_000L
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

        val url = "$BASE_URL?q=${URLEncoder.encode(keyword.trim(), "UTF-8")}"
        Log.i(TAG, "search: keyword='$keyword' url=$url")

        val root = HttpClient.getJson(url, perCallTimeoutMs = API_BUDGET_MS - 1_000L)
            ?: return@withContext SearchOutcome.Failure.sourceDown("响应为空或网络异常")

        try {
            val status = root.optString("status", "")
            if (status.isNotEmpty() && status != "success") {
                return@withContext SearchOutcome.Failure.sourceDown("API status: $status")
            }
            val resultsArr = root.optJSONArray("results")
                ?: return@withContext SearchOutcome.Success(emptyList())

            val results = mutableListOf<SearchResult>()
            val len = resultsArr.length()
            for (i in 0 until len) {
                val item = resultsArr.optJSONObject(i) ?: continue
                val title = item.optString("title", "")
                if (title.isBlank()) continue
                val author = item.optString("author", "")
                val description = item.optString("description", "")
                val downloadUrl = item.optString("url", "")
                if (downloadUrl.isBlank()) continue
                val cover = item.optString("cover", "")
                val bookId = item.optString("id", "")
                val fileType = if (downloadUrl.contains(".zip", true)) "zip" else "txt"

                val desc = StringBuilder()
                if (author.isNotEmpty()) desc.append("作者：$author")
                if (description.isNotEmpty() && description != "暂无简介") {
                    val snippet = if (description.length > 80) description.substring(0, 80) + "..." else description
                    desc.append(" · $snippet")
                }
                if (cover.isNotEmpty()) desc.append(" · 📦")

                results.add(
                    SearchResult(
                        id = "xiaoshuo-$bookId-$i",
                        title = title,
                        description = desc.toString(),
                        url = downloadUrl,
                        netDiskType = NetDiskType.DIRECT_URL,
                        size = "",
                        date = "",
                        sourceUrl = downloadUrl,
                        sourceName = displayName,
                        sourceId = id,
                        category = ResourceCategory.EBOOK,
                        fileType = fileType,
                        isValid = true,
                        requiresWebView = false,
                        extractionCode = null
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
}
