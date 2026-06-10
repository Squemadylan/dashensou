package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import com.dashensou.app.util.CategoryRules
import com.dashensou.app.util.FileTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.URLEncoder

/**
 * pan.club (网盘俱乐部) — 夸克 / 百度 / 阿里 三家聚合搜索.
 *
 * URL contract:
 *   - List (quark):  https://pan.club/s/0/0/{page}/{query}/
 *   - List (baidu):  https://pan.club/baidu/s/0/0/{page}/{query}/
 *   - List (alipan): https://pan.club/alipan/s/0/0/{page}/{query}/
 *   - Detail (quark):  https://pan.club/file/{hash}/
 *   - Detail (baidu):  https://pan.club/baidu/file/{id}/
 *   - Detail (alipan): https://pan.club/alipan/file/{id}/
 *
 * 列表阶段(在 [search] 中)只抓一次列表页,产出 title + detailUrl
 * 的纯清单 — 不会在搜索阶段二次抓详情。真正的网盘分享 URL 只有
 * 在用户点 "下载资源" 按钮后,经 [resolveShareUrl] 抓详情页拿到。
 *
 * 这么做的原因:pan.club 列表页里只渲染缩略图+标题,真实分享 URL
 * 是详情页 "打开网盘" 按钮 onclick 里 inline 的 pan.quark.cn /
 * pan.baidu.com / www.alipan.com 地址。列表阶段抓详情会:
 *   1) 把单源时间从 ~2s 拖到 ~7s,容易触发外层 2.5s/9s 预算超时;
 *   2) 把单次搜索的网络请求从 8 涨到 8 + 8*3 = 32;
 *   3) 很多用户根本不点开,白白浪费。
 * 而按需 fetch 不仅更快,还把"我想要这条资源"的用户意图明确化。
 *
 * The three concrete classes share all behaviour through [PanClubSearchBase];
 * they only differ in the per-disk configuration (host, basePath, id, displayName).
 */
private const val TAG = "PanClubSource"
private const val BASE_URL = "https://pan.club"
// pan.club ships up to 50 cards per page; in list-only mode we keep them
// all (no detail round-trip happens in search()).
private const val MAX_LIST_CARDS = 50
// Per-detail request timeout, used by [resolveShareUrl] when the user
// taps "下载资源" on a card. pan.club detail pages measured 1.3-2.5s
// round-trip from this workstation's fibre line; mobile carriers over
// 4G routinely add another 1-2s of latency to overseas origins. 2500ms
// is the lowest we can set without false positives on slower networks.
private const val DETAIL_TIMEOUT_MS = 8000L
// pan.club list page is HTML-rendered and takes 1.5-3s on its own, so
// we declare a slightly larger per-source budget than the 2.5s default.
// We no longer fetch detail pages during search — list + parse fit
// comfortably in 8s even on slow 4G.
private const val PANCLUB_BUDGET_MS = 8000L

/**
 * Real netdisk share URL (pan.quark.cn / pan.baidu.com / www.alipan.com)
 * plus its extraction code, lifted off a pan.club detail page. The
 * download handler hands [shareUrl] to the system clipboard and opens
 * the installed net-disk app; [password] is shown as a hint alongside.
 */
data class PanClubShare(
    val shareUrl: String,
    val password: String?
)

abstract class PanClubSearchBase : SearchSource {
    protected abstract val netDiskType: NetDiskType
    protected abstract val basePath: String

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }
        val encoded = try {
            URLEncoder.encode(keyword.trim(), "UTF-8")
        } catch (e: Exception) {
            keyword.trim()
        }
        val safePage = page.coerceAtLeast(1)
        val listUrl = "$BASE_URL$basePath/s/0/0/$safePage/$encoded/"
        Log.i(TAG, "[${displayName}] search '$keyword' page=$safePage url=$listUrl")

        val cards = try {
            fetchList(listUrl)
        } catch (e: IOException) {
            Log.e(TAG, "[${displayName}] IO failed: ${e.message}", e)
            return@withContext SearchOutcome.Failure.network("网络异常: ${e.message ?: "未知"}", e)
        } catch (e: Exception) {
            Log.e(TAG, "[${displayName}] parse failed: ${e.message}", e)
            return@withContext SearchOutcome.Failure.parse("解析失败: ${e.message ?: "未知"}", e)
        }
        Log.i(TAG, "[${displayName}] parsed ${cards.size} cards")

        if (cards.isEmpty()) {
            return@withContext SearchOutcome.Success(emptyList())
        }

        // List-only mode: we keep ALL cards from the list page. The
        // real netdisk share URL is not here — it has to be resolved
        // from a follow-up detail page hit when the user actually
        // taps "下载资源" on a card. Until then, SearchResult.url
        // points at the detail page itself, which the long-press
        // menu's "复制链接" / "在浏览器打开" can still act on as a
        // safe fallback.
        val results = cards.take(MAX_LIST_CARDS).mapIndexedNotNull { idx, card ->
            val title = card.title
            if (title.isBlank()) return@mapIndexedNotNull null
            val fileType = FileTypes.detectFromTitle(title)
            if (!CategoryRules.matchesByNetDisk(netDiskType, fileType, category)) {
                return@mapIndexedNotNull null
            }
            runCatching {
                SearchResult(
                    id = "$id-$idx-${card.detailUrl.hashCode()}",
                    title = title,
                    description = "${com.dashensou.app.util.DiskLabels.short(netDiskType)} · 未知大小",
                    // url is the detail page (not the share URL); the
                    // download handler is responsible for resolving the
                    // real share URL via [resolveShareUrl] before handing
                    // it to the system netdisk app.
                    url = card.detailUrl,
                    netDiskType = netDiskType,
                    size = "",
                    date = "",
                    sourceUrl = card.detailUrl,
                    sourceName = displayName,
                    sourceId = id,
                    category = category,
                    fileType = fileType,
                    isValid = true,
                    // Detail page is HTML — but we still want the
                    // "下载资源" button on the card to do the smart
                    // resolve + copy-to-clipboard flow, NOT pop a
                    // WebView. Keep this false.
                    requiresWebView = false,
                    // Extraction code can only be read off the detail
                    // page; leave null until the user actually taps.
                    extractionCode = null
                )
            }.getOrElse { e ->
                // SearchResult is a data class with no non-null invariants,
                // so a constructor failure here would mean a deeper bug
                // (e.g. a future validation rule). Log loudly and drop the
                // card rather than swallowing the exception silently.
                Log.e(TAG, "[${displayName}] card build failed: idx=$idx", e)
                null
            }
        }
        SearchOutcome.Success(results)
    }

    /**
     * One card on the list page — the title anchor plus its detail URL.
     * Netdisk share URL is intentionally NOT here: it has to be resolved
     * from a follow-up GET on the detail page (see [resolveShareUrl]).
     */
    private data class ListCard(
        val title: String,
        val detailUrl: String
    )

    private suspend fun fetchList(listUrl: String): List<ListCard> {
        val html = HttpClient.getString(listUrl, userAgent = USER_AGENT)
            ?: throw IOException("HTTP failed or empty body for $listUrl")
        val baseForJsoup = "$BASE_URL$basePath/"
        val document: Document = Jsoup.parse(html, baseForJsoup)
        return parseList(document)
    }

    private fun parseList(document: Document): List<ListCard> {
        val cards = mutableListOf<ListCard>()
        // The list page renders two anchors per card (thumbnail + title)
        // sharing the same href, so we dedupe to one entry per detail.
        val seenDetail = HashSet<String>()
        for (anchor in document.select("a.block[href*=/file/]")) {
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }
            if (href.isBlank()) continue
            val detailUrl = if (href.startsWith("http")) href else BASE_URL + href
            if (!seenDetail.add(detailUrl)) continue

            val titleRaw = anchor.attr("title").ifBlank { anchor.text() }
            // P1#perf: the title from pan.club is plain text in practice;
            // only route through Jsoup if we actually see HTML markup
            // (e.g. "<b>" or "&amp;") that needs decoding. Skipping
            // Jsoup.parseBodyFragment on the common path is the largest
            // single CPU save on this source.
            val title = if (titleRaw.indexOfFirst { it == '<' || it == '&' } >= 0) {
                Jsoup.parseBodyFragment(titleRaw).body().text().trim()
            } else {
                titleRaw.trim()
            }
            if (title.isBlank()) continue

            cards.add(
                ListCard(
                    title = title,
                    detailUrl = detailUrl
                )
            )
        }
        return cards
    }

    /**
     * GET a pan.club detail page and pull the real netdisk share URL
     * out of the "打开网盘" / "复制网盘链接" buttons' onclick attribute.
     * Returns [PanClubShare] on success, null on failure. Called from
     * the download handler when the user actually taps "下载资源" on
     * a card, NOT from search().
     */
    suspend fun resolveShareUrl(detailUrl: String): PanClubShare? = withContext(Dispatchers.IO) {
        val host = when (netDiskType) {
            NetDiskType.QUARK -> "pan.quark.cn"
            NetDiskType.BAIDU -> "pan.baidu.com"
            NetDiskType.ALIYUN -> "www.alipan.com"
            else -> ""
        }
        val body = HttpClient.getString(
            url = detailUrl,
            userAgent = USER_AGENT,
            perCallTimeoutMs = DETAIL_TIMEOUT_MS
        )
        if (body == null) {
            Log.w(TAG, "[${displayName}] detail fetch failed for $detailUrl")
            return@withContext null
        }
        val rawShare = extractShareUrlFromHtml(body, host)
        if (rawShare.isBlank()) {
            Log.w(TAG, "[${displayName}] no share url in $detailUrl")
            return@withContext null
        }
        val share = sanitizeShareUrl(rawShare)
        PanClubShare(share, extractPassword(share, host))
    }

    private fun extractShareUrlFromHtml(html: String, host: String): String {
        // 1) window.open('URL', '_blank')
        val windowOpen = Regex("""window\.open\(\s*['"]([^'"]+)['"]""")
        windowOpen.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        // 2) copyText('URL', 'message')
        val copyText = Regex("""copyText\(\s*['"]([^'"]+)['"]""")
        copyText.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        // 3) Bare URL of the disk's host anywhere in the page
        if (host.isNotBlank()) {
            val hostUrl = Regex("""['"](https?://[^'"]*${Regex.escape(host)}[^'"]*)['"]""")
            hostUrl.find(html)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return ""
    }

    private fun extractPassword(url: String, host: String): String? {
        // Baidu encodes its extraction code in ?pwd=xxx (or ?p=xxx on
        // older URLs). Treat literal "undefined" the same as missing.
        if (host != "pan.baidu.com") return null
        val qIndex = url.indexOf('?')
        if (qIndex < 0) return null
        val query = url.substring(qIndex + 1)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq < 0) continue
            val key = pair.substring(0, eq)
            if (key == "pwd" || key == "p") {
                val value = pair.substring(eq + 1)
                if (value.isBlank() || value == "undefined") return null
                return value
            }
        }
        return null
    }

    /**
     * 清洗从 pan.club 详情页里抠出来的网盘分享 URL。pan.club 服务
     * 端模板在密码缺失时把字面量 "undefined" 塞进 ?pwd=undefined,
     * 真实访问时这种 URL 是坏的(百度会报提取码错误)。我们:
     *   1) 整段 ?pwd=undefined 干掉
     *   2) 末尾残留的 ?、&、# 等清理
     *   3) 其它字段保留
     */
    private fun sanitizeShareUrl(url: String): String {
        if (url.isBlank()) return url
        var s = url
        // pan.club 的 "undefined 密码" 模板
        s = s.replace(Regex("""[?&]pwd=undefined(&|$)"""), "$1")
        s = s.replace(Regex("""[?&]p=undefined(&|$)"""), "$1")
        // 末尾的 ? & # 残留
        s = s.trimEnd('?', '&', '#')
        return s
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 DaShenSou/1.0"
    }
}

class PanClubQuarkSource : PanClubSearchBase() {
    override val id = "panclub_quark"
    override val displayName = "夸克网盘"
    override var enabled: Boolean = true
    override val netDiskType = NetDiskType.QUARK
    override val basePath: String = ""
    override val perSourceTimeoutMs: Long = PANCLUB_BUDGET_MS
}

class PanClubBaiduSource : PanClubSearchBase() {
    override val id = "panclub_baidu"
    override val displayName = "百度网盘"
    override var enabled: Boolean = true
    override val netDiskType = NetDiskType.BAIDU
    override val basePath: String = "/baidu"
    override val perSourceTimeoutMs: Long = PANCLUB_BUDGET_MS
}

class PanClubAlipanSource : PanClubSearchBase() {
    override val id = "panclub_alipan"
    override val displayName = "阿里云盘"
    override var enabled: Boolean = true
    override val netDiskType = NetDiskType.ALIYUN
    override val basePath: String = "/alipan"
    override val perSourceTimeoutMs: Long = PANCLUB_BUDGET_MS
}
