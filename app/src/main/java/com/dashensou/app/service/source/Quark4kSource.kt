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
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Quark4k 论坛搜索源（Flarum API）。
 * 对应 PanHub server/core/plugins/quark4k.ts。
 *
 * 搜索 quark4k.com 论坛里的夸克分享帖子，解析帖子内容中的夸克网盘链接+提取码。
 * 仅返回 QUARK 类型结果；其他网盘类型直接跳过。
 */
class Quark4kSource : SearchSource {

    override val id = "quark4k"
    override val displayName = "夸克论坛"
    override var enabled: Boolean = true
    override val perSourceTimeoutMs: Long = 12_000L

    companion object {
        private const val TAG = "Quark4kSource"
        private const val BASE_URL = "https://quark4k.com/api/discussions"
        private const val REFERER = "https://quark4k.com/"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120 Safari/537.36"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        // Flarum API 参数：包含帖子正文和标签信息
        private const val INCLUDE_PARAM =
            "user%2ClastPostedUser%2CmostRelevantPost%2CmostRelevantPost.user%2Ctags%2Ctags.parent%2CfirstPost"
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext SearchOutcome.Success(emptyList())
        val kw = keyword.trim()

        val url = "$BASE_URL?include=$INCLUDE_PARAM" +
            "&filter[q]=${java.net.URLEncoder.encode(kw, "UTF-8")}" +
            "&sort=&page[offset]=0&page[limit]=50"

        val request = Request.Builder()
            .url(url)
            .header("Referer", REFERER)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .get()
            .build()

        val response = HttpClient.execute(request, perSourceTimeoutMs)
            ?: return@withContext SearchOutcome.Failure.network("网络异常")
        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext SearchOutcome.Failure.sourceDown("HTTP ${resp.code}")
            }
            val body = resp.body?.string()
                ?: return@withContext SearchOutcome.Failure.sourceDown("响应体为空")

            return@withContext try {
                val json = JSONObject(body)
                parseResponse(json, kw)
            } catch (e: Exception) {
                Log.w(TAG, "parse failed: ${e.message}")
                SearchOutcome.Failure.parse("解析失败: ${e.message}", e)
            }
        }
    }

    /**
     * 独立导出以便单测（无需网络）。
     */
    internal fun parseResponse(json: JSONObject, kw: String): SearchOutcome {
        val data = json.optJSONArray("data") ?: return SearchOutcome.Success(emptyList())
        val included = json.optJSONArray("included") ?: JSONArray()

        // Build post map: post id -> post data
        val postMap = mutableMapOf<String, JSONObject>()
        for (i in 0 until included.length()) {
            val obj = included.optJSONObject(i) ?: continue
            val type = obj.optString("type")
            val id = obj.optString("id")
            if (type == "posts" && id.isNotEmpty()) {
                postMap[id] = obj
            }
        }

        val results = mutableListOf<SearchResult>()
        for (i in 0 until data.length()) {
            val disc = data.optJSONObject(i) ?: continue
            val attrs = disc.optJSONObject("attributes") ?: continue
            val title = attrs.optString("title", "").trim()
            if (title.isEmpty() || !title.lowercase(Locale.ROOT).contains(kw.lowercase(Locale.ROOT))) continue

            val postId = disc.optJSONObject("relationships")
                ?.optJSONObject("mostRelevantPost")
                ?.optJSONObject("data")
                ?.optString("id") ?: ""

            val post: JSONObject? = if (postId.isNotEmpty()) postMap[postId] else null
            val contentHtml = post?.optJSONObject("attributes")
                ?.optString("contentHtml")
                ?: attrs.optString("contentHtml", "")
            if (contentHtml.isEmpty()) continue

            val cleaned = cleanHTML(contentHtml)
            if (cleaned.isEmpty()) continue

            val links = extractLinksFromText(cleaned)
            if (links.isEmpty()) continue

            val lines = cleaned.split("\n")
            val finalLinks = links.map { link ->
                if (link.type != "quark") return@map link
                val idx = lines.indexOfFirst { it.contains(link.url) }
                val pwd = if (idx >= 0) findPasswordNear(lines, idx) else ""
                link.copy(password = pwd)
            }

            val createdAt = attrs.optString("createdAt", "")
            val datetime = if (createdAt.isNotEmpty()) isoToDate(createdAt) else ""

            for (link in finalLinks) {
                if (link.type != "quark") continue
                results.add(
                    SearchResult(
                        id = "quark4k-${link.url.hashCode()}",
                        title = title.take(200),
                        description = cleaned.take(160),
                        url = link.url,
                        netDiskType = NetDiskType.QUARK,
                        date = datetime,
                        sourceUrl = "https://quark4k.com/",
                        sourceName = displayName,
                        sourceId = id,
                        category = ResourceCategory.NETDISK,
                        isValid = true,
                        extractionCode = link.password.ifEmpty { null }
                    )
                )
            }
            if (results.size >= 50) break
        }

        Log.i(TAG, "kw='$kw' results=${results.size}")
        return SearchOutcome.Success(results)
    }

    private fun isoToDate(iso: String): String {
        return try {
            val dt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(iso)
            if (dt != null) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(dt) else iso
        } catch (_: Exception) { iso }
    }

    /** Flatten HTML to plain text lines (ported from panLink.ts cleanHTML). */
    private fun cleanHTML(html: String): String {
        var s = html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "\n")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        return s.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** Find extraction code near a line containing [url]. */
    private fun findPasswordNear(lines: List<String>, urlLineIdx: Int): String {
        val start = maxOf(0, urlLineIdx - 2)
        val end = minOf(lines.size - 1, urlLineIdx + 2)
        for (i in start..end) {
            val m = Regex("""(?:提取码|密码)[:：]\s*([0-9a-zA-Z]{4,12})""").find(lines[i])
            if (m != null) return m.groupValues[1]
        }
        return ""
    }

    /**
     * Extract quark/aliyun/baidu/netdisk links from plain text.
     * Restores JSON-escaped slashes (`https:\/\/x` → `https://x`).
     */
    private fun extractLinksFromText(text: String): List<LinkInfo> {
        val normalized = com.dashensou.app.util.UrlKinds.unescapeJsonUrl(text)
        val found = linkedMapOf<String, LinkInfo>()
        val urlRe = Regex("""https?://[^\s"'<>)\]]+""")
        for (match in urlRe.findAll(normalized)) {
            val url = match.value
            val type = getLinkType(url)
            if (type == "others" && !url.startsWith("magnet:") && !url.startsWith("ed2k:")) continue
            if (!found.containsKey(url)) found[url] = LinkInfo(type = type, url = url, password = "")
        }
        return found.values.toList()
    }

    private data class LinkInfo(val type: String, val url: String, val password: String)

    private fun getLinkType(url: String): String {
        val u = url.lowercase(Locale.ROOT)
        if (u.startsWith("magnet:")) return "magnet"
        if (u.startsWith("ed2k://")) return "ed2k"
        if (u.startsWith("thunder://")) return "thunder"
        return when {
            u.contains("pan.quark.cn") -> "quark"
            u.contains("drive.uc.cn") -> "uc"
            u.contains("pan.baidu.com") -> "baidu"
            u.contains("aliyundrive.com") || u.contains("alipan.com") -> "aliyun"
            u.contains("pan.xunlei.com") -> "xunlei"
            u.contains("cloud.189.cn") -> "tianyi"
            u.contains("115.com") -> "115"
            u.contains("123pan.com") -> "123"
            u.contains("yun.139.com") || u.contains("feixin.10086.cn") || u.contains("caiyun") -> "mobile"
            u.contains("share.weiyun.com") -> "weiyun"
            u.contains("lanzou") || u.contains("lanzo") -> "lanzou"
            u.contains("jianguoyun.com") -> "jianguoyun"
            u.contains("mypikpak.com") -> "pikpak"
            else -> "others"
        }
    }
}
