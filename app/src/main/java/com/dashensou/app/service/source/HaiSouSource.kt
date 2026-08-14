package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import com.dashensou.app.service.source.SearchOutcome
import com.dashensou.app.service.source.SearchSource
import com.dashensou.app.util.CategoryRules
import com.dashensou.app.util.FileTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Resolved real net-disk share produced by [resolveRealShare]: the actual
 * share URL on the hosting net-disk (quark/baidu/xunlei/...) plus the file
 * extraction code (提取码, i.e. the `?pwd=` value) for that share.
 */
data class HaiSouResolvedShare(val url: String, val pwd: String?)

/**
 * Build a best-effort net-disk share URL directly from the API's
 * `share_code` + `platform`. Used as a fallback when the detail page
 * can't be scraped. Patterns verified against live haisou detail pages.
 */
fun buildHaiSouRealUrl(platform: String, code: String): String? {
    if (code.isBlank()) return null
    return when (platform.lowercase()) {
        "quark" -> "https://pan.quark.cn/s/$code"
        "baidu" -> "https://pan.baidu.com/s/$code"
        "xunlei" -> "https://pan.xunlei.com/s/$code"
        "ali", "aliyun" -> "https://www.aliyundrive.com/s/$code"
        "123", "123pan" -> "https://www.123pan.com/s/$code"
        "tianyi" -> "https://cloud.189.cn/t/$code"
        "yidong", "139" -> "https://yun.139.com/shareweb/%23/w/i/$code"
        else -> null
    }
}

/**
 * haisou.cc via the site's v2 REST search API.
 *
 * haisou.cc is a Nuxt SPA — the server returns only the app shell and the
 * result list is fetched client-side from `/api/v2/shares/search`. Plain
 * OkHttp scraping of the HTML sees an empty `<div id="app">`, and calling the
 * API from inside the WebView via `fetch()` proved unreliable on Android
 * (returns `{}` instead of the array). So we POST the API directly with
 * OkHttp — structured JSON, fast, **no WebView dependency**.
 *
 * API contract (reverse-engineered from the site's client bundle):
 *   POST /api/v2/shares/search
 *   body: { query, filters:{scope:'title', include_filtered:false},
 *           pagination:{page, page_size:20} }
 *   `scope:'all'` was discontinued by the site ("全部搜索模式已下线").
 *   response: { success:true, data:{ items:[ {hsid, platform,
 *             share_name, share_code, share_pwd, stat_file, stat_size}, ... ] } }
 */
class HaiSouSource : SearchSource {

    override val id = "haisou"
    override val displayName = "海搜"
    override var enabled: Boolean = true
    override val perSourceTimeoutMs: Long = 20_000L

    private val baseUrl = "https://haisou.cc"
    private val apiUrl = "$baseUrl/api/v2/shares/search"
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory,
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext SearchOutcome.Success(emptyList())

        val kw = keyword.trim()
        val bodyJson = JSONObject().apply {
            put("query", kw)
            put("filters", JSONObject().apply {
                put("scope", "title")
                put("include_filtered", false)
            })
            put("pagination", JSONObject().apply {
                put("page", page)
                put("page_size", 20)
            })
        }.toString()

        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", HttpClient.DEFAULT_UA)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/?q=" + URLEncoder.encode(kw, "UTF-8"))
            .post(bodyJson.toRequestBody(mediaType))
            .build()

        Log.i(TAG, "search: keyword='$kw' page=$page -> POST $apiUrl")

        val response = HttpClient.execute(request, perSourceTimeoutMs)
            ?: run {
                Log.w(TAG, "execute returned null -> 网络异常 (timeout/IO/SSL)")
                return@withContext SearchOutcome.Failure.sourceDown("网络异常")
            }

        val bodyStr = response.use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "search -> HTTP ${resp.code}")
                return@withContext SearchOutcome.Failure.sourceDown("海搜接口 HTTP ${resp.code}")
            }
            resp.body?.string()
        }
        if (bodyStr.isNullOrBlank()) {
            Log.w(TAG, "response body is null/blank")
            return@withContext SearchOutcome.Failure.sourceDown("响应为空")
        }

        try {
            val root = JSONObject(bodyStr)
            // haisou v2 API returns { success:true, data:{ items:[...] } },
            // NOT the { code:0, data:{ rows:[...] } } shape assumed earlier.
            val success = root.optBoolean("success", false)
            val data = root.optJSONObject("data")
            val items = data?.optJSONArray("items") ?: JSONArray()
            if (!success && items.length() == 0) {
                val msg = root.optString("message", root.optString("msg", "未知错误"))
                Log.w(TAG, "api error: $msg  body=${bodyStr.take(300)}")
                return@withContext SearchOutcome.Failure.sourceDown("海搜接口错误: $msg")
            }
            val results = mutableListOf<SearchResult>()
            for (i in 0 until items.length()) {
                val o = items.getJSONObject(i)
                val hsid = o.optString("hsid", "")
                val shareName = stripHighlight(o.optString("share_name", ""))
                if (shareName.isBlank() || hsid.isBlank()) continue

                val platform = o.optString("platform", "")
                val codeV = o.optString("share_code", "")
                val pwd = o.optString("share_pwd", "")
                val sizeBytes = o.optLong("stat_size", 0L)
                val fileCount = o.optLong("stat_file", 0L)
                val detailUrl = "$baseUrl/s/$hsid"

                val netDiskType = mapPlatform(platform)
                val fileType = FileTypes.detectFromTitle(shareName)
                if (!CategoryRules.matchesByNetDisk(netDiskType, fileType, category)) continue

                val desc = buildString {
                    if (codeV.isNotBlank()) append("分享码:$codeV")
                    if (pwd.isNotBlank()) append(" 提取码:$pwd")
                    if (fileCount > 0) append(" 文件数:$fileCount")
                }
                results.add(
                    SearchResult(
                        id = "haisou-$i-$hsid",
                        title = shareName,
                        description = desc,
                        // Best-effort real net-disk link; the tap-time
                        // resolveRealShare() overrides this with the
                        // authoritative URL + access code from the detail page.
                        url = buildHaiSouRealUrl(platform, codeV) ?: detailUrl,
                        netDiskType = netDiskType,
                        size = if (sizeBytes > 0) formatSize(sizeBytes) else "",
                        date = "",
                        // Keep the haisou detail URL here so the download
                        // flow can recover the hsid for detail resolution.
                        sourceUrl = detailUrl,
                        sourceName = displayName,
                        sourceId = id,
                        category = category,
                        fileType = fileType,
                        isValid = true,
                        requiresWebView = false,
                        extractionCode = pwd.ifBlank { null },
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

    /**
     * Remove highlight markup the API wraps around matched keywords,
     * e.g. `<span class="highlight">三国</span>` -> `三国`.
     */
    private fun stripHighlight(s: String): String {
        if (s.isEmpty()) return s
        return s.replace(Regex("<[^>]+>"), "").trim()
    }

    /**
     * Map haisou's `platform` field (from the v2 API) to our NetDiskType.
     * Observed values: "quark", "ali", "baidu", "123", "xunlei", ...
     */
    private fun mapPlatform(platform: String): NetDiskType {
        return when (platform.lowercase()) {
            "baidu" -> NetDiskType.BAIDU
            "quark" -> NetDiskType.QUARK
            "ali", "aliyun" -> NetDiskType.ALIYUN
            "123", "123pan" -> NetDiskType.YUNPAN123
            "xunlei" -> NetDiskType.XUNLEI
            else -> NetDiskType.OTHER
        }
    }

    /**
     * Resolve the real net-disk share URL + file extraction code (提取码) for
     * a haisou result. The list/search API returns `share_code` but always a
     * null `share_pwd`; the authoritative extraction code lives behind
     * haisou's detail-page "邀请码" gate and is only exposed by the dedicated
     * `POST /api/v2/shares/{hsid}/fetch` endpoint, which returns
     * `{ data: { platform, share_code, share_pwd } }`. The real hosting URL is
     * then reconstructed client-side from `platform` + `share_code` (exactly
     * how haisou.cc's own web app builds it).
     *
     * Returns null if the endpoint errors or yields no usable share_code.
     */
    suspend fun resolveRealShare(hsid: String): HaiSouResolvedShare? =
        withContext(Dispatchers.IO) {
            val fetchUrl = "$baseUrl/api/v2/shares/$hsid/fetch"
            Log.i(TAG, "resolveRealShare: POST $fetchUrl")
            val request = Request.Builder()
                .url(fetchUrl)
                .header("User-Agent", HttpClient.DEFAULT_UA)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Origin", baseUrl)
                .header("Referer", "$baseUrl/s/$hsid")
                .post("{}".toRequestBody(mediaType))
                .build()

            val response = HttpClient.execute(request, perSourceTimeoutMs)
                ?: run {
                    Log.w(TAG, "resolveRealShare: execute returned null -> 网络异常")
                    return@withContext null
                }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "resolveRealShare: HTTP ${resp.code}")
                    return@withContext null
                }
                val bodyStr = resp.body?.string()
                if (bodyStr.isNullOrBlank()) {
                    Log.w(TAG, "resolveRealShare: empty body")
                    return@withContext null
                }
                try {
                    val root = JSONObject(bodyStr)
                    val data = root.optJSONObject("data")
                        ?: run {
                            Log.w(TAG, "resolveRealShare: no data object")
                            return@withContext null
                        }
                    val platform = data.optString("platform", "")
                    val code = data.optString("share_code", "")
                    val pwd = data.optString("share_pwd", "")
                    val realUrl = buildHaiSouRealUrl(platform, code)
                        ?: run {
                            Log.w(TAG, "resolveRealShare: no real url for platform='$platform' code='$code'")
                            return@withContext null
                        }
                    Log.i(TAG, "resolveRealShare hit: $realUrl pwd=${pwd.ifBlank { "(none)" }}")
                    HaiSouResolvedShare(realUrl, pwd.ifBlank { null })
                } catch (e: Exception) {
                    Log.e(TAG, "resolveRealShare parse failed: ${e.message}", e)
                    null
                }
            }
        }

    /**
     * Human-readable byte size, e.g. 295459406678 -> "275.16GB".
     */
    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var i = 0
        while (size >= 1024 && i < units.lastIndex) {
            size /= 1024
            i++
        }
        return String.format("%.2f%s", size, units[i])
    }

    companion object {
        private const val TAG = "HaiSouSource"
    }
}
