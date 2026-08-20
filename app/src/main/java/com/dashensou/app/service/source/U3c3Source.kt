package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale

/**
 * u3c3 磁力搜索源。
 * 对应 PanHub server/core/plugins/u3c3.ts。
 *
 * GET https://u3c3u3c3.u3c3u3c3.u3c3.com/?search=<kw>
 * 返回 HTML，结果在 <table class="torrent-list"> 中。
 * 注意：此源仅提供磁力链接（magnet），不涵盖普通网盘分享。
 */
class U3c3Source : SearchSource {

    override val id = "u3c3"
    override val displayName = "u3c3磁力"
    override var enabled: Boolean = false  // 默认关闭，磁力源用户按需开启
    override val perSourceTimeoutMs: Long = 12_000L

    companion object {
        private const val TAG = "U3c3Source"
        // 真实站点 u3c3.com（域名常轮换，镜像：u3c3.vip / u3c3.in / u3c3.live / u3c3.cc；
        // 最新域名见发布页 https://github.com/u3c3/bt-btt）。原代码中
        // "u3c3u3c3.u3c3u3c3.u3c3.com" 为拼接占位笔误，已修正为规范域名。
        private const val BASE_URL = "https://u3c3.com/?search="
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120 Safari/537.36"
        private val MAGNET_RE = Regex("""href="(magnet:\?xt=urn:btih:[^"]+)""", RegexOption.IGNORE_CASE)
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext SearchOutcome.Success(emptyList())
        val kw = keyword.trim()

        val url = "$BASE_URL${URLEncoder.encode(kw, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "text/html,application/xhtml+xml,*/*")
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
                val results = parseHtml(body, kw)
                SearchOutcome.Success(results)
            } catch (e: Exception) {
                Log.w(TAG, "parse failed: ${e.message}")
                SearchOutcome.Failure.parse("解析失败: ${e.message}", e)
            }
        }
    }

    internal fun parseHtml(html: String, kw: String): List<SearchResult> {
        val doc: Document = Jsoup.parse(html)
        val table = doc.select("table.torrent-list").first()
            ?: return emptyList()
        val rows = table.select("tr").filter { el -> !el.select(":matchesOwn(^\\s*$)").isEmpty() }
        val results = mutableListOf<SearchResult>()
        for (row in rows) {
            if (row.select("th").isNotEmpty()) continue  // skip header
            val tds = row.select("td")
            if (tds.size < 2) continue

            val nameEl: Element? = tds[1]
            val name = nameEl?.text()?.trim()?.ifBlank { kw }?.take(200) ?: continue

            // Find magnet link in the row (can be in any td)
            val magMatch = MAGNET_RE.find(row.html())
                ?: continue
            val url = magMatch.groupValues[1]

            // Extract date from last td if present
            val dateStr = if (tds.size >= 5) tds[4].text().trim() else ""
            val date = if (dateStr.isNotEmpty()) normalizeDate(dateStr) else ""

            // Clean name for display
            val cleanName = name
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("\\(.*?\\)"), "")
                .replace(Regex("\\{.*?\\}"), "")
                .trim()

            results.add(
                SearchResult(
                    id = "u3c3-${url.hashCode()}",
                    title = cleanName.ifBlank { name },
                    description = name,
                    url = url,
                    netDiskType = NetDiskType.OTHER,
                    date = date,
                    sourceUrl = BASE_URL,
                    sourceName = displayName,
                    sourceId = id,
                    category = ResourceCategory.ALL,  // 磁力链不归入 NETDISK tab
                    isValid = true
                )
            )
            if (results.size >= 50) break
        }
        Log.i(TAG, "kw='$kw' results=${results.size}")
        return results
    }

    private fun normalizeDate(s: String): String {
        // Try ISO format first
        val isoFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        isoFmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val date = isoFmt.parse(s)
        if (date != null) {
            val outFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            return outFmt.format(date)
        }
        // Try common formats
        val fmts = listOf(
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "MM/dd/yyyy",
            "dd/MM/yyyy"
        )
        for (fmt in fmts) {
            try {
                val d = java.text.SimpleDateFormat(fmt, Locale.US).parse(s)
                if (d != null) {
                    val out = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                    return out.format(d)
                }
            } catch (_: Exception) {}
        }
        return s
    }
}
