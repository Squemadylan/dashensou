package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class XiaoShuoApiSource(
    private val client: OkHttpClient = defaultClient()
) : SearchSource {

    override val id = "xiaoshuo"
    override val displayName = "爱下电子书 (直链)"
    override var enabled: Boolean = true

    companion object {
        private const val TAG = "XiaoShuoApiSource"
        private const val BASE_URL = "https://api.xcvts.cn/api/xiaoshuo/axdzs"
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

        val url = "$BASE_URL?q=${URLEncoder.encode(keyword.trim(), "UTF-8")}"
        Log.i(TAG, "search: keyword='$keyword' url=$url")

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val response = client.newCall(request).execute()
            Log.i(TAG, "response: code=${response.code} bodyLength=${response.body?.contentLength() ?: -1}")

            if (!response.isSuccessful) {
                return@withContext SearchOutcome.Failure("HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: return@withContext SearchOutcome.Failure("响应体为空")
            val root = try {
                JSONObject(body)
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse failed: ${body.take(200)}", e)
                return@withContext SearchOutcome.Failure("JSON 解析失败", e)
            }
            val status = root.optString("status", "")
            if (status.isNotEmpty() && status != "success") {
                return@withContext SearchOutcome.Failure("API status: $status")
            }
            val resultsArr = root.optJSONArray("results")
                ?: return@withContext SearchOutcome.Success(emptyList())

            val results = mutableListOf<SearchResult>()
            val len = resultsArr.length()
            for (i in 0 until len) {
                val item = resultsArr.optJSONObject(i)
                if (item == null) continue
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
        } catch (e: IOException) {
            Log.e(TAG, "IO failed: ${e.message}", e)
            SearchOutcome.Failure("网络异常: ${e.message ?: "未知"}", e)
        } catch (e: Exception) {
            Log.e(TAG, "failed: ${e.message}", e)
            SearchOutcome.Failure("解析失败: ${e.message ?: "未知"}", e)
        }
    }
}
