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

class PanSouSource(
    private val baseUrl: String = DEFAULT_BASE_URL
) : SearchSource {

    override val id = "pansou_252"
    override val displayName = "网盘来源"
    override var enabled: Boolean = false
    // P0#timeout: `/api/search` with `src=all` fans out to every
    // upstream mirror and routinely takes 5-20s (measured 2026-06).
    // The default 2.5s SearchService budget and the shared 12s OkHttp
    // read timeout both expire before the API can respond.
    override val perSourceTimeoutMs: Long = API_BUDGET_MS

    companion object {
        private const val TAG = "PanSouSource"
        const val DEFAULT_BASE_URL = "https://so.252035.xyz"
        private const val API_BUDGET_MS = 25_000L
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }

        Log.i(TAG, "search: keyword='$keyword' page=$page category=$category")

        val json = JSONObject().apply {
            put("kw", keyword.trim())
            put("res", "merge")
            put("src", "all")
        }

        val url = "$baseUrl/api/search"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json; charset=utf-8")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val response = HttpClient.execute(request, API_BUDGET_MS)
            ?: return@withContext SearchOutcome.Failure.network("网络异常")
        response.use { resp ->
            if (resp.code == 429) {
                return@withContext SearchOutcome.Failure.sourceDown("网盘来源API限流,请稍后再试")
            }
            if (!resp.isSuccessful) {
                val msg = when (resp.code) {
                    400 -> "关键词可能不支持,请换词重试 (HTTP 400)"
                    else -> "HTTP ${resp.code}"
                }
                return@withContext SearchOutcome.Failure.sourceDown(msg)
            }
            val body = resp.body?.string()
                ?: return@withContext SearchOutcome.Failure.sourceDown("响应体为空")

            val root = try {
                Json.parseObject(body)
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse failed: ${body.take(200)}", e)
                return@withContext SearchOutcome.Failure.parse("JSON 解析失败", e)
            }

            if (root.optInt("code", -1) != 0) {
                val msg = root.optString("message", "未知错误")
                return@withContext SearchOutcome.Failure.sourceDown("API: $msg")
            }

            val data = root.optJSONObject("data")
                ?: return@withContext SearchOutcome.Success(emptyList())

            val merged = data.optJSONObject("merged_by_type")
                ?: return@withContext SearchOutcome.Success(emptyList())

            val results = mutableListOf<SearchResult>()
            val keys = merged.keys()
            while (keys.hasNext()) {
                val type = keys.next()
                val arr = merged.optJSONArray(type) ?: continue
                val netDisk = mapCloudType(type)
                val len = arr.length()
                for (i in 0 until len) {
                    val item = arr.optJSONObject(i) ?: continue
                    val urlStr = com.dashensou.app.util.UrlKinds.unescapeJsonUrl(item.optString("url", ""))
                    if (urlStr.isBlank()) continue
                    val note = item.optString("note", "")
                    val effectiveTitle = if (note.isBlank()) type else note
                    val source = item.optString("source", "")
                    // PanSou 把密码放在两处:有时是 `password` 字段,
                    // 经常是 URL 的 `?pwd=xxxx`(baidu / xunlei 几乎都这样)。
                    // 如果字段为空,回退到 URL query string 里抽。
                    val pwdFromField = item.optString("password", "")
                    val pwdFromUrl = extractPwdFromUrl(urlStr)
                    val pwd = pwdFromField.ifBlank { pwdFromUrl.orEmpty() }
                    val date = item.optString("datetime", "")
                    val finalDate = if (date.isBlank() || date == "0001-01-01T00:00:00Z") "" else date
                    val fullTitle = if (source.isBlank()) effectiveTitle else "$effectiveTitle · $source"
                    val fileType = detectFileType(fullTitle, type)
                    if (!CategoryRules.matchesByNetDisk(netDisk, fileType, category)) continue
                    results.add(
                        SearchResult(
                            id = "pansou-$type-$i-${urlStr.hashCode()}",
                            title = fullTitle,
                            description = "${type.uppercase()} · $source",
                            url = urlStr,
                            netDiskType = netDisk,
                            size = "",
                            date = finalDate,
                            sourceUrl = urlStr,
                            sourceName = displayName,
                            sourceId = id,
                            category = category,
                            fileType = fileType,
                            isValid = true,
                            requiresWebView = false,
                            extractionCode = pwd.ifBlank { null }
                        )
                    )
                }
            }
            Log.i(TAG, "parsed: count=${results.size}")
            SearchOutcome.Success(results)
        }
    }

    private fun mapCloudType(type: String): NetDiskType = when (type.lowercase()) {
        "baidu" -> NetDiskType.BAIDU
        "aliyun", "aliyunpan" -> NetDiskType.ALIYUN
        "quark" -> NetDiskType.QUARK
        "tianyi" -> NetDiskType.OTHER
        "uc" -> NetDiskType.OTHER
        "115" -> NetDiskType.OTHER
        "xunlei" -> NetDiskType.XUNLEI
        "123", "123pan" -> NetDiskType.YUNPAN123
        "pikpak" -> NetDiskType.OTHER
        "mobile" -> NetDiskType.OTHER
        "magnet" -> NetDiskType.OTHER
        "ed2k" -> NetDiskType.OTHER
        else -> NetDiskType.OTHER
    }

    private fun detectFileType(title: String, type: String): String? {
        if (type.equals("magnet", true)) return "video"
        return FileTypes.detectFromTitle(title)
    }

    /**
     * PanSou 返回的 baidu / xunlei 链接经常把密码塞在 `?pwd=xxxx` 里
     * (有的源在字段 `password` 里,有的在 URL query 里,有的两边都有,
     * 也有的都没有)。这里从 URL 的 query string 里尝试拿一次,
     * 字段优先、URL 兜底。
     *
     * 返回值用 `String?` 让上层区分"没有码"和"码为空串"。
     */
    private fun extractPwdFromUrl(url: String): String? {
        if (url.isBlank()) return null
        val lower = url.trim().lowercase()
        if (lower.startsWith("magnet:") || lower.startsWith("ed2k:")) return null
        return try {
            val uri = Uri.parse(url)
            val fromPwd = uri.getQueryParameter("pwd")
            val fromPwdUpper = uri.getQueryParameter("PWD")
            val fromPassword = uri.getQueryParameter("password")
            (fromPwd ?: fromPwdUpper ?: fromPassword)?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "extractPwdFromUrl failed for $url: ${e.message}")
            null
        }
    }
}
