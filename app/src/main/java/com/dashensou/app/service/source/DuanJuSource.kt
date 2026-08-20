package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.net.HttpClient
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.util.CategoryRules
import com.dashensou.app.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder

/**
 * 短剧搜索源 (duanju.click)。
 *
 * 两个子接口：
 *   /api/short/quark  → 夸克网盘链接
 *   /api/short/baidu  → 百度网盘链接
 *
 * 两者并发请求，结果按 URL 去重合并。
 * 短剧属于网盘内容，归类为 [ResourceCategory.NETDISK]。
 */
class DuanJuSource : SearchSource {

    override val id = "duanju"
    override val displayName = "短剧源"
    override var enabled: Boolean = false
    override val perSourceTimeoutMs: Long = 8_000L

    companion object {
        private const val TAG = "DuanJuSource"
        private const val BASE_URL = "https://www.duanju.click"
        private const val PATH_QUARK = "/api/short/quark"
        private const val PATH_BAIDU = "/api/short/baidu"
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }

        Log.i(TAG, "search: keyword='$keyword' page=$page")

        val encoded = try {
            URLEncoder.encode(keyword.trim(), "UTF-8")
        } catch (e: Exception) {
            keyword.trim()
        }

        val results = coroutineScope {
            val quarkDeferred = async { fetchQuark(encoded) }
            val baiduDeferred = async { fetchBaidu(encoded) }
            val quarkResults = quarkDeferred.await()
            val baiduResults = baiduDeferred.await()
            // Merge, preferring QUARK when the same title appears in both APIs.
            val merged = mutableListOf<SearchResult>()
            val seen = mutableSetOf<String>()
            // Add QUARK results first (they tend to have better links).
            merged.addAll(quarkResults.filter { seen.add(it.url) })
            merged.addAll(baiduResults.filter { seen.add(it.url) })
            merged
        }

        Log.i(TAG, "merged: total=${results.size}")
        SearchOutcome.Success(results)
    }

    private suspend fun fetchQuark(encoded: String): List<SearchResult> =
        fetchOne("$BASE_URL$PATH_QUARK?text=$encoded", NetDiskType.QUARK)

    private suspend fun fetchBaidu(encoded: String): List<SearchResult> =
        fetchOne("$BASE_URL$PATH_BAIDU?text=$encoded", NetDiskType.BAIDU)

    private suspend fun fetchOne(url: String, netDiskType: NetDiskType): List<SearchResult> {
        val request = Request.Builder().url(url).get().build()
        val response = HttpClient.execute(request, perSourceTimeoutMs) ?: run {
            Log.w(TAG, "request failed [$netDiskType]: 网络异常")
            return emptyList()
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "HTTP ${resp.code} for $netDiskType")
                return emptyList()
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) {
                Log.w(TAG, "empty body for $netDiskType")
                return emptyList()
            }

            return try {
                parseResponse(body, netDiskType)
            } catch (e: Exception) {
                Log.e(TAG, "parse failed [$netDiskType]: ${e.message}")
                emptyList()
            }
        }
    }

    private fun parseResponse(body: String, netDiskType: NetDiskType): List<SearchResult> {
        val root = Json.parseObject(body)
        if (root.optInt("code", -1) != 200) {
            val msg = root.optString("msg", "未知错误")
            Log.w(TAG, "API error [$netDiskType]: $msg")
            return emptyList()
        }

        val data = root.optJSONArray("data") ?: return emptyList()
        val results = mutableListOf<SearchResult>()

        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val name = item.optString("name", "").trim()
            val link = item.optString("link", "").trim()
            val time = item.optString("time", "").trim()

            if (link.isBlank()) continue

            // Extract the extraction code from Baidu URLs (pwd=xxxx).
            val extractionCode = extractBaiduPwd(link)

            // Detect file type: short drama titles often contain "(88集)", "(100集)".
            val fileType = if (name.contains(Regex("""\d+集"""))) "video" else null

            // Short dramas are netdisk content.
            if (!CategoryRules.matchesByNetDisk(netDiskType, fileType, ResourceCategory.NETDISK)) {
                continue
            }

            results.add(
                SearchResult(
                    id = "duanju-$netDiskType-$i-${link.hashCode()}",
                    title = name,
                    description = "",
                    url = link,
                    netDiskType = netDiskType,
                    size = "",
                    date = time,
                    sourceUrl = link,
                    sourceName = displayName,
                    sourceId = id,
                    category = ResourceCategory.NETDISK,
                    fileType = fileType,
                    isValid = true,
                    requiresWebView = false,
                    extractionCode = extractionCode
                )
            )
        }

        return results
    }

    /**
     * Extract `pwd=xxxx` from a Baidu netdisk URL. Returns null if not found.
     */
    private fun extractBaiduPwd(url: String): String? {
        if (!url.contains("baidu.com")) return null
        return try {
            val m = Regex("""[?&]pwd=([^&\s]+)""").find(url)
            m?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }
}
