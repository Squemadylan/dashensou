package com.dashensou.app.service.source

import android.net.Uri
import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import com.dashensou.app.util.CategoryRules
import com.dashensou.app.util.FileTypes
import com.dashensou.app.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URLEncoder
import java.util.Locale

/**
 * 夸克搜 (kkso.net) — from awesome-zhuiju-free `cloud_search`.
 *
 * Contract (live-probed 2026-08):
 *   GET /api/search?title={kw}
 *   { code:200, data:{ total_result, items:[{ id, title, url, code, times, name }] } }
 *
 * Note: `kw` / `q` / `keyword` are ignored by the API and return a dump of
 * recent rows; only `title` actually filters.
 */
class KksoSource(
    private val baseUrl: String = DEFAULT_BASE_URL
) : SearchSource {

    override val id = "kkso"
    override val displayName = "夸克搜"
    override var enabled: Boolean = true
    override val perSourceTimeoutMs: Long = 12_000L

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext SearchOutcome.Success(emptyList())

        val kw = keyword.trim()
        val encoded = URLEncoder.encode(kw, "UTF-8")
        val url = "$baseUrl/api/search?title=$encoded"
        Log.i(TAG, "search: keyword='$kw' -> GET $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClient.DEFAULT_UA)
            .header("Accept", "application/json")
            .header("Referer", "$baseUrl/")
            .get()
            .build()

        val response = HttpClient.execute(request, perSourceTimeoutMs)
            ?: return@withContext SearchOutcome.Failure.network("网络异常")

        val body = response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext SearchOutcome.Failure.sourceDown("夸克搜 HTTP ${resp.code}")
            }
            resp.body?.string()
        }
        if (body.isNullOrBlank()) {
            return@withContext SearchOutcome.Failure.sourceDown("响应体为空")
        }

        val root = try {
            Json.parseObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${body.take(200)}", e)
            return@withContext SearchOutcome.Failure.parse("JSON 解析失败", e)
        }

        if (root.optInt("code", -1) != 200) {
            val msg = root.optString("message", "未知错误")
            return@withContext SearchOutcome.Failure.sourceDown("API: $msg")
        }

        val data = root.optJSONObject("data")
            ?: return@withContext SearchOutcome.Success(emptyList())
        val items = data.optJSONArray("items")
            ?: return@withContext SearchOutcome.Success(emptyList())

        val results = mutableListOf<SearchResult>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val shareUrl = item.optString("url", "").trim()
            if (shareUrl.isBlank() || !shareUrl.startsWith("http", ignoreCase = true)) continue

            val title = item.optString("title")
                .ifBlank { item.optString("name") }
                .ifBlank { shareUrl }
            val netDisk = classifyUrl(shareUrl)
            val fileType = FileTypes.detectFromTitle(title)
            if (!CategoryRules.matchesByNetDisk(netDisk, fileType, category)) continue

            val codeField = item.optString("code", "")
            val pwd = codeField.ifBlank { extractPwdFromUrl(shareUrl).orEmpty() }
            val date = item.optString("times", "")
            val idHint = item.opt("id")?.toString()?.ifBlank { null } ?: i.toString()

            results.add(
                SearchResult(
                    id = "kkso-$idHint-${shareUrl.hashCode()}",
                    title = title,
                    description = "夸克搜",
                    url = shareUrl,
                    netDiskType = netDisk,
                    date = date,
                    sourceUrl = shareUrl,
                    sourceName = displayName,
                    sourceId = id,
                    category = ResourceCategory.NETDISK,
                    fileType = fileType,
                    isValid = true,
                    extractionCode = pwd.ifBlank { null }
                )
            )
        }

        Log.i(TAG, "parsed: count=${results.size} total=${data.optInt("total_result", -1)}")
        SearchOutcome.Success(results)
    }

    companion object {
        private const val TAG = "KksoSource"
        const val DEFAULT_BASE_URL = "https://kkso.net"

        private fun classifyUrl(url: String): NetDiskType {
            val host = runCatching { Uri.parse(url).host.orEmpty().lowercase(Locale.ROOT) }
                .getOrDefault("")
            return when {
                host == "pan.baidu.com" -> NetDiskType.BAIDU
                host == "pan.quark.cn" || host == "drive.quark.cn" -> NetDiskType.QUARK
                host.endsWith("alipan.com") || host.endsWith("aliyundrive.com") -> NetDiskType.ALIYUN
                host == "pan.xunlei.com" -> NetDiskType.XUNLEI
                host.contains("123pan") || host.contains("123684") ||
                    host.contains("123685") || host.contains("123912") -> NetDiskType.YUNPAN123
                else -> NetDiskType.OTHER
            }
        }

        private fun extractPwdFromUrl(url: String): String? {
            return try {
                val uri = Uri.parse(url)
                (uri.getQueryParameter("pwd")
                    ?: uri.getQueryParameter("PWD")
                    ?: uri.getQueryParameter("password"))
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }
}
