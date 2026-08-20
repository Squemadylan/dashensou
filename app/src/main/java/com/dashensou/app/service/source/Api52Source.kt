package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import com.dashensou.app.util.CategoryRules
import com.dashensou.app.util.FileTypes
import com.dashensou.app.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

class Api52Source(
    private val apiKey: String = DEFAULT_API_KEY
) : SearchSource {

    override val id = "api52"
    override val displayName = "聚合搜索"
    override var enabled: Boolean = false

    companion object {
        private const val TAG = "Api52Source"
        private const val BASE_URL = "https://www.52api.cn/api/pan_sou"

        const val DEFAULT_API_KEY = "9NgmhC1V0qlTl4LLelQ8jJn7Xk"

        private const val CODE_QUARK = "0"
        private const val CODE_BAIDU = "1"
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }
        if (apiKey.isBlank()) {
            return@withContext SearchOutcome.Failure.sourceDown("未配置 52API 密钥")
        }

        Log.i(TAG, "search: keyword='$keyword' page=$page category=$category")

        val merged = coroutineScope {
            val quarkDeferred = async(Dispatchers.IO) {
                fetchByCode(keyword, page, category, CODE_QUARK)
            }
            val baiduDeferred = async(Dispatchers.IO) {
                fetchByCode(keyword, page, category, CODE_BAIDU)
            }
            val quark = quarkDeferred.await()
            val baidu = baiduDeferred.await()
            val combined = mutableListOf<SearchResult>()
            if (quark is SearchOutcome.Success) combined.addAll(quark.results)
            if (baidu is SearchOutcome.Success) combined.addAll(baidu.results)
            combined
        }

        if (merged.isEmpty()) {
            SearchOutcome.Success(emptyList())
        } else {
            Log.i(TAG, "merged total: count=${merged.size}")
            SearchOutcome.Success(merged)
        }
    }

    private suspend fun fetchByCode(
        keyword: String,
        page: Int,
        category: ResourceCategory,
        code: String
    ): SearchOutcome = withContext(Dispatchers.IO) {
        val url = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .addQueryParameter("type", "search")
            .addQueryParameter("keyword", keyword.trim())
            .addQueryParameter("code", code)
            .build()
            .toString()
        Log.d(TAG, "request code=$code url=$url")

        val request = HttpClient.newGet(url).newBuilder()
            .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .build()
        val response = HttpClient.execute(request)
            ?: return@withContext SearchOutcome.Failure.network("网络异常")
        response.use { resp ->
            val code_http = resp.code
            val body = resp.body?.string()
                ?: return@withContext SearchOutcome.Failure.sourceDown("响应体为空")

            if (code_http == 429 || code_http == 403) {
                return@withContext SearchOutcome.Failure.sourceDown("HTTP $code_http (限流)")
            }
            if (!resp.isSuccessful) {
                return@withContext SearchOutcome.Failure.sourceDown("HTTP $code_http")
            }

            val root = try {
                Json.parseObject(body)
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse failed: ${body.take(200)}", e)
                return@withContext SearchOutcome.Failure.parse("JSON 解析失败", e)
            }

            val apiCode = root.optInt("code", -1)
            if (apiCode != 0) {
                val msg = root.optString("msg", "未知错误")
                Log.w(TAG, "API error code=$apiCode msg=$msg")
                if (apiCode == 502 || msg.contains("频率") || msg.contains("限制") || msg.contains("限额")) {
                    return@withContext SearchOutcome.Failure.sourceDown("限流: $msg")
                }
                if (apiCode == 400) {
                    return@withContext SearchOutcome.Failure.sourceDown("参数错误: $msg")
                }
                return@withContext SearchOutcome.Failure.sourceDown("API: $msg")
            }

            val dataStr = root.optString("data", "")
            if (dataStr.isBlank()) {
                return@withContext SearchOutcome.Success(emptyList())
            }

            val dataObj = try {
                Json.parseObject(dataStr)
            } catch (e: Exception) {
                Log.e(TAG, "data JSON parse failed: ${dataStr.take(200)}", e)
                return@withContext SearchOutcome.Success(emptyList())
            }

            val results = mutableListOf<SearchResult>()
            val keys = dataObj.keys()
            while (keys.hasNext()) {
                val type = keys.next()
                val arr = dataObj.optJSONArray(type) ?: continue
                val netDisk = when (type.lowercase()) {
                    "baidu" -> NetDiskType.BAIDU
                    "quark" -> NetDiskType.QUARK
                    "xunlei" -> NetDiskType.XUNLEI
                    "aliyun", "aliyunpan" -> NetDiskType.ALIYUN
                    "123", "123pan" -> NetDiskType.YUNPAN123
                    "uc" -> NetDiskType.OTHER
                    "115" -> NetDiskType.OTHER
                    "ty", "tianyi" -> NetDiskType.OTHER
                    else -> NetDiskType.OTHER
                }
                val len = arr.length()
                for (i in 0 until len) {
                    val item = arr.optJSONObject(i) ?: continue
                    val rawId = item.optString("id", "")
                    val name = item.optString("name", "")
                    if (name.isBlank()) continue
                    val fullTitle = name
                    val fileType = FileTypes.detectFromTitle(fullTitle)
                    if (!CategoryRules.matchesByNetDisk(netDisk, fileType, category)) continue
                    results.add(
                        SearchResult(
                            id = "api52-${type}-$i-${rawId.hashCode()}",
                            title = fullTitle,
                            description = "${type.uppercase()}",
                            url = "api52://$type/$rawId/${java.net.URLEncoder.encode(name, "UTF-8")}",
                            netDiskType = netDisk,
                            size = "",
                            date = "",
                            sourceUrl = "https://www.52api.cn",
                            sourceName = displayName,
                            sourceId = id,
                            category = category,
                            fileType = fileType,
                            isValid = true,
                            requiresWebView = true,
                            extractionCode = null
                        )
                    )
                }
            }
            Log.d(TAG, "code=$code parsed: count=${results.size}")
            SearchOutcome.Success(results)
        }
    }

    suspend fun fetchShareUrl(type: String, id: String, name: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .addQueryParameter("type", "share")
                .addQueryParameter("code", if (type.equals("quark", true)) CODE_QUARK else CODE_BAIDU)
                .addQueryParameter("id", id)
                .addQueryParameter("name", name)
                .build()
                .toString()
            Log.i(TAG, "fetchShareUrl: $url")
            val response = HttpClient.execute(HttpClient.newGet(url))
                ?: return@withContext null
            response.use { resp ->
                val body = resp.body?.string() ?: return@withContext null
                val root = Json.parseObject(body)
                if (root.optInt("code", -1) != 0) {
                    Log.w(TAG, "share failed: ${root.optString("msg", "未知")}")
                    return@withContext null
                }
                val dataStr = root.optString("data", "")
                if (dataStr.isBlank()) return@withContext null
                try {
                    val dataObj = Json.parseObject(dataStr)
                    dataObj.optString("url", dataStr)
                } catch (e: Exception) {
                    dataStr
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "fetchShareUrl failed", e)
            null
        }
    }
}
