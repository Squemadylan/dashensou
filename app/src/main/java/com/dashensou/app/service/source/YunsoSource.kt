package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Yunso 网盘搜索源。
 * 对应 PanHub server/core/plugins/yunso.ts。
 *
 * POST https://www.yunso.net/api/Core/search2
 * 返回 JSON: { code: 0, data: "<html>..." }
 * HTML 中每条结果形如: <a url="..." pa="密码">标题</a>
 */
class YunsoSource : SearchSource {

    override val id = "yunso"
    override val displayName = "云搜"
    override var enabled: Boolean = false
    override val perSourceTimeoutMs: Long = 12_000L

    companion object {
        private const val TAG = "YunsoSource"
        private const val BASE_URL = "https://www.yunso.net/api/Core/search2"
        private const val REFERER = "https://www.yunso.net/"
        private const val ORIGIN = "https://www.yunso.net/"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120 Safari/537.36"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val ANCHOR_RE = Regex("""<a\b[^>]*\burl="([^"]+)"[^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext SearchOutcome.Success(emptyList())
        val kw = keyword.trim()

        val body = """{"wd":"${kw.replace("\"", "\\\"")}", "page":1}"""
        val request = Request.Builder()
            .url(BASE_URL)
            .header("Content-Type", "application/json")
            .header("Origin", ORIGIN)
            .header("Referer", REFERER)
            .header("User-Agent", UA)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        val response = HttpClient.execute(request, perSourceTimeoutMs)
            ?: return@withContext SearchOutcome.Failure.network("网络异常")
        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext SearchOutcome.Failure.sourceDown("HTTP ${resp.code}")
            }
            val bodyStr = resp.body?.string()
                ?: return@withContext SearchOutcome.Failure.sourceDown("响应体为空")
            return@withContext try {
                parseBody(bodyStr, kw)
            } catch (e: Exception) {
                Log.w(TAG, "parse failed: ${e.message}")
                SearchOutcome.Failure.parse("解析失败: ${e.message}", e)
            }
        }
    }

    private fun parseBody(body: String, kw: String): SearchOutcome {
        // Extract JSON portion
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) {
            return SearchOutcome.Success(emptyList())
        }
        val jsonStr = body.substring(start, end + 1)
        val json = org.json.JSONObject(jsonStr)
        val code = json.optInt("code", -1)
        if (code != 0) {
            val msg = json.optString("message", "未知错误")
            return SearchOutcome.Failure.sourceDown("API: $msg")
        }
        val html = json.optString("data", "")
        if (html.isEmpty()) return SearchOutcome.Success(emptyList())
        return SearchOutcome.Success(parseHtml(html, kw))
    }

    internal fun parseHtml(html: String, kw: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val iterator = ANCHOR_RE.findAll(html).iterator()
        while (iterator.hasNext() && results.size < 50) {
            val match = iterator.next()
            val rawUrl = com.dashensou.app.util.UrlKinds.unescapeJsonUrl(match.groupValues[1]).trim()
            if (rawUrl.isEmpty()) continue
            val type = resolveNetDiskType(rawUrl)
            // Only keep netdisk / magnet links
            if (type == NetDiskType.OTHER && !rawUrl.startsWith("magnet:") && !rawUrl.startsWith("ed2k:")) continue

            val passwordMatch = Regex("""pa="([^"]*)"""", RegexOption.IGNORE_CASE).find(match.value)
            val password = passwordMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()

            val title = cleanText(match.groupValues[2]).ifBlank { kw }.take(200)

            results.add(
                SearchResult(
                    id = "yunso-${rawUrl.hashCode()}",
                    title = title,
                    description = title,
                    url = rawUrl,
                    netDiskType = type,
                    sourceUrl = BASE_URL,
                    sourceName = displayName,
                    sourceId = id,
                    category = ResourceCategory.NETDISK,
                    isValid = true,
                    extractionCode = if (password.isNotEmpty()) password else null
                )
            )
        }
        Log.i(TAG, "kw='$kw' results=${results.size}")
        return results
    }

    private fun resolveNetDiskType(url: String): NetDiskType {
        val u = url.lowercase()
        return when {
            u.contains("pan.quark.cn") || u.contains("drive.quark.cn") -> NetDiskType.QUARK
            u.contains("pan.baidu.com") -> NetDiskType.BAIDU
            u.contains("aliyundrive.com") || u.contains("alipan.com") -> NetDiskType.ALIYUN
            u.contains("pan.xunlei.com") -> NetDiskType.XUNLEI
            u.contains("123pan.com") -> NetDiskType.YUNPAN123
            u.contains("cloud.189.cn") -> NetDiskType.OTHER
            u.contains("drive.uc.cn") -> NetDiskType.OTHER
            u.contains("115.com") -> NetDiskType.OTHER
            else -> NetDiskType.OTHER
        }
    }

    private fun cleanText(html: String): String {
        return html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
