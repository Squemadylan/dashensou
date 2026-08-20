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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

/**
 * PanSou (pansou.de) — from awesome-zhuiju-free `cloud_search`.
 *
 * Distinct from [PanSouSource] (`so.252035.xyz`), which uses `{kw,res,src}`
 * and `merged_by_type`. This site expects:
 *   POST /api/search  body: { "query": "..." }
 *   { results:[{ id, url, title, password, datetime, source }], total, cached }
 */
class PansouDeSource(
    private val baseUrl: String = DEFAULT_BASE_URL
) : SearchSource {

    override val id = "pansou_de"
    override val displayName = "盘搜.de"
    override var enabled: Boolean = true
    override val perSourceTimeoutMs: Long = 20_000L

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext SearchOutcome.Success(emptyList())

        val kw = keyword.trim()
        val payload = JSONObject().put("query", kw).toString()
        val url = "$baseUrl/api/search"
        Log.i(TAG, "search: keyword='$kw' -> POST $url")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", HttpClient.DEFAULT_UA)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        val response = HttpClient.execute(request, perSourceTimeoutMs)
            ?: return@withContext SearchOutcome.Failure.network("网络异常")

        val body = response.use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                val msg = runCatching {
                    Json.parseObject(err).optString("error")
                }.getOrNull().orEmpty().ifBlank { "HTTP ${resp.code}" }
                return@withContext SearchOutcome.Failure.sourceDown("盘搜.de $msg")
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

        if (root.has("error") && root.optString("error").isNotBlank()) {
            return@withContext SearchOutcome.Failure.sourceDown(
                "盘搜.de ${root.optString("error")}"
            )
        }

        val items = root.optJSONArray("results")
            ?: return@withContext SearchOutcome.Success(emptyList())

        val results = mutableListOf<SearchResult>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val shareUrl = com.dashensou.app.util.UrlKinds.unescapeJsonUrl(item.optString("url", "")).trim()
            if (shareUrl.isBlank() || !shareUrl.startsWith("http", ignoreCase = true)) continue

            val title = item.optString("title").ifBlank { shareUrl }
            val netDisk = classifyUrl(shareUrl)
            val fileType = FileTypes.detectFromTitle(title)
            if (!CategoryRules.matchesByNetDisk(netDisk, fileType, category)) continue

            val pwdField = item.optString("password", "")
            val pwd = pwdField.ifBlank { extractPwdFromUrl(shareUrl).orEmpty() }
            val source = item.optString("source", "")
            val date = item.optString("datetime", "")
                .take(19)
                .replace('T', ' ')
            val idHint = item.optString("id").ifBlank { i.toString() }

            results.add(
                SearchResult(
                    id = "pansou_de-$idHint-${shareUrl.hashCode()}",
                    title = if (source.isBlank()) title else "$title · $source",
                    description = source.ifBlank { "盘搜.de" },
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

        Log.i(TAG, "parsed: count=${results.size} total=${root.optInt("total", -1)}")
        SearchOutcome.Success(results)
    }

    companion object {
        private const val TAG = "PansouDeSource"
        const val DEFAULT_BASE_URL = "https://pansou.de"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

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
