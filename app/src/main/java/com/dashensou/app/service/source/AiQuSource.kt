package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder

class AiQuSource : SearchSource {

    override val id = "aiqu225"
    override val displayName = "电子书搜索"
    override var enabled: Boolean = true
    // aiqu225 列表页 1.5-2.5s 起步,中文 GBK 编码 + 慢 CDN,手机
    // 网络下经常贴 2.5s 默认预算边缘失败。给到 5s 让它稳定返回。
    override val perSourceTimeoutMs: Long = 5_000L

    companion object {
        private const val TAG = "AiQuSource"
        private const val BASE_URL = "https://www.aiqu225.com"
        // aiqu 服务的页面是 GBK 编码,UTF-8 解码会乱码成不可识别字符
        // 导致 Jsoup 选择器一个都匹配不到。所有 GET 都必须带这个
        // charset,别忘了 resolveSoftdownUrl / fetchFirstTxtMirror。
        private const val CHARSET = "GBK"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 DaShenSou/1.0"
    }

    data class DetailInfo(
        val netDiskType: NetDiskType,
        val password: String?,
        val gotoUrl: String
    )

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }
        if (category != ResourceCategory.ALL && category != ResourceCategory.EBOOK) {
            return@withContext SearchOutcome.Success(emptyList())
        }
        val encoded = try {
            URLEncoder.encode(keyword.trim(), CHARSET)
        } catch (e: Exception) {
            keyword.trim()
        }
        val url = "$BASE_URL/search.asp?word=$encoded"
        Log.i(TAG, "search: keyword='$keyword' url=$url")

        val html = HttpClient.getString(url, userAgent = USER_AGENT, charset = CHARSET)
        if (html == null) {
            return@withContext SearchOutcome.Failure.sourceDown("响应为空或网络异常")
        }
        try {
            val document = Jsoup.parse(html, BASE_URL)
            val results = parseSearchResults(document)
            Log.i(TAG, "parsed: count=${results.size}")
            SearchOutcome.Success(results)
        } catch (e: Exception) {
            Log.e(TAG, "failed: ${e.message}", e)
            SearchOutcome.Failure.parse("解析失败: ${e.message ?: "未知"}", e)
        }
    }

    suspend fun fetchDetail(detailUrl: String): DetailInfo? = withContext(Dispatchers.IO) {
        // The site's book detail page only contains an entry link to a separate
        // download page (softdownfree.asp). The actual mirror URLs (.txt files)
        // live on that second page, NOT on the detail page itself. So the
        // resolution is a two-step fetch:
        //   detail page  ->  /txt-xx/softdownfree.asp?softid={id}&ckm=mianfei
        //   download page ->  http(s)://*.downbook*.com/.../{title}.txt
        try {
            Log.i(TAG, "fetchDetail: $detailUrl")
            val softdownUrl = resolveSoftdownUrl(detailUrl)
                ?: run {
                    Log.w(TAG, "fetchDetail: no softdown entry found on detail page")
                    return@withContext null
                }
            val mirror = fetchFirstTxtMirror(softdownUrl)
                ?: run {
                    Log.w(TAG, "fetchDetail: no .txt mirror on softdown page")
                    return@withContext null
                }
            Log.i(TAG, "fetchDetail hit: $mirror")
            DetailInfo(NetDiskType.DIRECT_URL, null, mirror)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDetail failed", e)
            null
        }
    }

    /**
     * Step 1: load the book detail page, find the
     *   <a href="/txt-xx/softdownfree.asp?softid={id}&ckm=mianfei">免费下载本小说</a>
     * entry, and return the absolute URL of the download page.
     */
    private suspend fun resolveSoftdownUrl(detailUrl: String): String? {
        val html = HttpClient.getString(detailUrl, userAgent = USER_AGENT, charset = CHARSET)
            ?: run {
                Log.w(TAG, "resolveSoftdownUrl: empty body")
                return null
            }
        val document = Jsoup.parse(html, detailUrl)
        val link = document.selectFirst("a[href*=/txt-xx/softdownfree.asp]") ?: return null
        val absHref = link.attr("abs:href").ifBlank { link.attr("href") }
        return if (absHref.startsWith("http")) absHref else BASE_URL + absHref
    }

    /**
     * Step 2: load the download page and pick the preferred .txt mirror.
     *
     * The softdownfree page lists up to five mirror links per book in this
     * order, all rendered in a single table cell as plain anchor tags:
     *
     *   在线阅读(最新排版版,无错)        http://yd*.downbook*.com/.../{title}.txt
     *   第一下载地址(首选)                  http(s)://txt.downbook*.com/.../{title}.txt
     *   第二下载地址(备用)                  http(s)://txt*.downbook*.com/.../{title}.txt
     *   第三下载地址(备用)                  http(s)://txt*s.downbook*.com/.../{title}.txt
     *   第四下载地址(备用)                  http(s)://txt.downbook*.com/.../{title}.txt
     *
     * "在线阅读" link points at an HTML reader (yd*.downbook* host), NOT at
     * a raw .txt download — handing it to DownloadManager produces a file
     * whose contents are the online reader page rather than the novel. We
     * therefore prefer the explicitly labelled "第N下载地址" anchor (which
     * points at a real .txt file on a txt*.downbook* host) and only fall
     * back to the first .txt-suffix link if none of the labelled ones are
     * present (older / stripped layouts).
     */
    private suspend fun fetchFirstTxtMirror(softdownUrl: String): String? {
        val html = HttpClient.getString(softdownUrl, userAgent = USER_AGENT, charset = CHARSET)
            ?: run {
                Log.w(TAG, "fetchFirstTxtMirror: empty body")
                return null
            }
        val document = Jsoup.parse(html, softdownUrl)
        val anchors = document.select("a[href]")

        // 1) Prefer an anchor whose text contains "第一下载地址"
        val firstLabel = anchors.firstOrNull { a ->
            a.text().contains("第一下载地址")
        }
        firstLabel?.let { a ->
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isNotBlank()) {
                Log.i(TAG, "fetchFirstTxtMirror: picked 第一下载地址 -> $href")
                return href
            }
        }

        // 2) Walk 第N下载 addresses in declared order — these all sit
        //    on the txt*.downbook* mirror CDN and are real .txt files.
        val labels = listOf("第二下载地址", "第三下载地址", "第四下载地址", "第五下载地址")
        for (label in labels) {
            val a = anchors.firstOrNull { it.text().contains(label) }
            if (a != null) {
                val href = a.absUrl("href").ifBlank { a.attr("href") }
                if (href.isNotBlank()) {
                    Log.i(TAG, "fetchFirstTxtMirror: picked $label -> $href")
                    return href
                }
            }
        }

        // 3) Last-resort: any anchor ending in .txt on an http(s) URL.
        //    This branch is reached only for layouts without the labelled
        //    download anchors; do not move it ahead of the labelled
        //    search above or we end up serving the "在线阅读" reader page.
        for (a in anchors) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isBlank()) continue
            if (href.startsWith("http") && href.lowercase().endsWith(".txt")) {
                Log.w(TAG, "fetchFirstTxtMirror: falling back to first .txt anchor -> $href")
                return href
            }
        }
        return null
    }

    private fun parseSearchResults(document: Document): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val cards = document.select("div.search-card")
        Log.d(TAG, "matched search-card: ${cards.size}")

        for (card in cards) {
            try {
                val titleEl = card.selectFirst("a.searchtitle") ?: continue
                val title = titleEl.text().trim().let { Jsoup.parseBodyFragment(it).body().text() }
                if (title.isEmpty()) continue
                val href = titleEl.attr("href")
                val detailUrl = if (href.startsWith("http")) href else BASE_URL + href
                val author = card.selectFirst(".search-card-author")?.text()
                    ?.replace("作者：", "")?.replace("作者:", "")?.trim() ?: ""
                val cat = card.selectFirst(".search-card-category a")?.text()?.trim()
                    ?: card.selectFirst(".search-card-category")?.text()?.trim() ?: ""
                val date = card.selectFirst(".oldDate")?.text()?.trim()
                    ?: card.selectFirst(".search-card-date")?.text()?.trim() ?: ""
                val content = card.selectFirst(".search-card-content")?.text()?.trim() ?: ""

                val desc = StringBuilder()
                if (author.isNotEmpty()) desc.append("作者：$author")
                if (cat.isNotEmpty()) desc.append(" · $cat")
                if (content.isNotEmpty()) {
                    val snippet = if (content.length > 80) content.substring(0, 80) + "..." else content
                    desc.append(" · $snippet")
                }

                results.add(
                    SearchResult(
                        id = "aiqu-${detailUrl.hashCode()}",
                        title = title,
                        description = desc.toString(),
                        url = detailUrl,
                        // aiqu 返回 .txt 小说直链,不是网盘分享。
                        // 标成 DIRECT_URL 让网盘/电子书 tab 过滤时
                        // 把它正确归到"电子书"那一边。
                        netDiskType = NetDiskType.DIRECT_URL,
                        size = "",
                        date = date,
                        sourceUrl = detailUrl,
                        sourceName = displayName,
                        sourceId = id,
                        category = ResourceCategory.EBOOK,
                        fileType = "txt",
                        isValid = true,
                        requiresWebView = true,
                        extractionCode = null
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "parse card failed", e)
            }
        }

        return results
    }
}
