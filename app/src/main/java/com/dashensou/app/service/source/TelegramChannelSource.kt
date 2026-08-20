package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import com.dashensou.app.util.CategoryRules
import com.dashensou.app.util.FileTypes
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Telegram public-channel scraper (t.me/s/{channel}).
 *
 * Important limitation: Telegram's public preview is **not a search API**.
 * We only see the newest posts (plus a few `?before=` pages). A hit appears
 * only when the keyword shows up in that recent window — so "小黄人" can
 * legitimately return empty even when older channel history has it.
 *
 * Strategy:
 *  - Fan out across curated channels with bounded concurrency
 *  - Race direct t.me HTML vs r.jina.ai mirror; reuse the winner for paging
 *  - Walk up to [pagesPerChannel] history pages via `?before=`
 *  - If every channel is unreachable, surface SOURCE_DOWN (not a quiet empty)
 */
class TelegramChannelSource(
    private val channels: List<String> = DEFAULT_PRIORITY_CHANNELS,
    private val concurrency: Int = 4,
    private val limitPerChannel: Int = 40,
    private val pagesPerChannel: Int = 2
) : SearchSource {

    override val id = "telegram"
    override val displayName = "TG频道"
    override var enabled: Boolean = false
    override val perSourceTimeoutMs: Long = 25_000L

    private data class ChannelOutcome(
        val results: List<SearchResult>,
        val reached: Boolean
    )

    private enum class Transport { DIRECT, MIRROR }

    private data class PageLoad(
        val body: String,
        val transport: Transport,
        val htmlWidget: Boolean
    )

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) return@withContext SearchOutcome.Success(emptyList())
        val kw = keyword.trim()
        val gate = Semaphore(concurrency.coerceIn(1, 6))
        val unreachable = AtomicInteger(0)
        val errors = AtomicInteger(0)

        val batches = coroutineScope {
            channels.map { channel ->
                async {
                    gate.withPermit {
                        try {
                            val outcome = fetchChannel(channel, kw)
                            if (!outcome.reached) unreachable.incrementAndGet()
                            outcome.results
                        } catch (t: Throwable) {
                            errors.incrementAndGet()
                            Log.w(TAG, "channel @$channel failed: ${t.message}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll()
        }

        val merged = batches.flatten()
            .distinctBy { it.url.lowercase(Locale.ROOT) }
            .filter { CategoryRules.matches(it, category) }

        val down = unreachable.get() + errors.get()
        Log.i(
            TAG,
            "keyword='$kw' channels=${channels.size} hits=${merged.size} " +
                "unreachable=${unreachable.get()} errors=${errors.get()}"
        )

        when {
            merged.isNotEmpty() -> SearchOutcome.Success(merged)
            down >= channels.size ->
                SearchOutcome.Failure.sourceDown(
                    "无法访问 Telegram（t.me / 镜像均超时），请检查网络或关掉 TG 源"
                )
            else -> SearchOutcome.Success(emptyList())
        }
    }

    private suspend fun fetchChannel(channel: String, keyword: String): ChannelOutcome {
        val out = LinkedHashMap<String, SearchResult>()
        var before: Long? = null
        var transport: Transport? = null
        var reached = false

        repeat(pagesPerChannel.coerceIn(1, 5)) {
            val page = loadPage(channel, before, transport)
            if (page == null) {
                // First page unreachable → channel is down. Do NOT keep
                // retrying later pages (that burned the whole source budget
                // on phones that cannot reach t.me / jina at all).
                if (!reached) return ChannelOutcome(emptyList(), reached = false)
                return ChannelOutcome(out.values.toList(), reached = true)
            }
            reached = true
            transport = page.transport

            val parsed = if (page.htmlWidget) {
                parseChannelHtml(page.body, channel, keyword, limitPerChannel)
            } else {
                parseChannelPlainText(page.body, channel, keyword, limitPerChannel)
            }
            for (item in parsed) {
                out.putIfAbsent(item.url.lowercase(Locale.ROOT), item)
            }
            if (out.size >= limitPerChannel) return ChannelOutcome(out.values.toList(), true)

            val oldest = oldestPostId(page.body)
            val prevBefore = before
            if (oldest == null || (prevBefore != null && oldest >= prevBefore)) {
                return ChannelOutcome(out.values.toList(), true)
            }
            before = oldest
            // Mirror reader pages rarely expose stable before= cursors.
            if (page.transport == Transport.MIRROR && !page.htmlWidget) {
                return ChannelOutcome(out.values.toList(), true)
            }
        }

        return ChannelOutcome(out.values.toList(), reached)
    }

    /**
     * Load one preview page. First call races direct vs mirror; later calls
     * stick to the transport that already worked for this channel.
     */
    private suspend fun loadPage(
        channel: String,
        before: Long?,
        preferred: Transport?
    ): PageLoad? = coroutineScope {
        val encoded = URLEncoder.encode(channel, "UTF-8")
        val target = if (before == null) {
            "https://t.me/s/$encoded"
        } else {
            "https://t.me/s/$encoded?before=$before"
        }

        suspend fun direct(): PageLoad? {
            val body = HttpClient.getString(
                target,
                userAgent = UA,
                perCallTimeoutMs = CALL_TIMEOUT_MS
            ) ?: return null
            if (!body.contains("tgme_widget_message")) return null
            return PageLoad(body, Transport.DIRECT, htmlWidget = true)
        }

        suspend fun mirror(): PageLoad? {
            // Encode the whole target so `?before=` is not eaten by jina.
            val mirrored = "https://r.jina.ai/" + URLEncoder.encode(target, "UTF-8")
            val body = HttpClient.getString(
                mirrored,
                userAgent = UA,
                perCallTimeoutMs = CALL_TIMEOUT_MS
            ) ?: return null
            return if (body.contains("tgme_widget_message")) {
                PageLoad(body, Transport.MIRROR, htmlWidget = true)
            } else {
                PageLoad(body, Transport.MIRROR, htmlWidget = false)
            }
        }

        when (preferred) {
            Transport.DIRECT -> direct()
            Transport.MIRROR -> mirror()
            null -> {
                val d = async { direct() }
                val m = async { mirror() }
                awaitFirstNotNull(d, m)
            }
        }
    }

    private suspend fun <T : Any> awaitFirstNotNull(
        first: Deferred<T?>,
        second: Deferred<T?>
    ): T? {
        val pending = mutableListOf(first, second)
        while (pending.isNotEmpty()) {
            val (job, value) = select {
                pending.forEach { d ->
                    d.onAwait { result -> d to result }
                }
            }
            pending.remove(job)
            if (value != null) {
                pending.forEach { it.cancel() }
                return value
            }
        }
        return null
    }

    companion object {
        private const val TAG = "TelegramChannelSource"
        private const val CALL_TIMEOUT_MS = 3_500L
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        /**
         * Curated TG public channels. Keep the set tight so the source can
         * finish inside [perSourceTimeoutMs] on mobile. Base = PanHub;
         * extras = netdisk-spider HIGH only (MID/LOW omitted).
         */
        val DEFAULT_PRIORITY_CHANNELS: List<String> = listOf(
            "tgsearchers3",
            "share_aliyun",
            "Quark_Movies",
            "NewQuark",
            "yunpanqk",
            "BaiduCloudDisk",
            "Aliyun_4K_Movies",
            "Quark_Share_Channel",
            "bdyunpan",
            "PanjClub",
            "yunpanbaidu",
            "QuarkShare",
            "kuakeshare",
            "yunpanNB",
            "leoziyuan"
        )

        private val URL_PATTERN =
            Regex("""https?://[A-Za-z0-9\-._~:/?#\[\]@!$&'()*+,;=%]+""")
        private val PASSWD_PATTERN =
            Regex("""(?:提取码|密码|pwd|pass)[:：\s]*([a-zA-Z0-9]{3,6})""", RegexOption.IGNORE_CASE)
        private val POST_ID_PATTERN =
            Regex("""data-post="[^"/]+/(\d+)""")

        internal fun oldestPostId(body: String): Long? =
            POST_ID_PATTERN.findAll(body)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .minOrNull()

        internal fun parseChannelHtml(
            html: String,
            channel: String,
            keyword: String,
            limit: Int
        ): List<SearchResult> {
            val doc = Jsoup.parse(html)
            val out = mutableListOf<SearchResult>()
            val wraps = doc.select(".tgme_widget_message_wrap")
            for ((index, el) in wraps.withIndex()) {
                if (out.size >= limit) break
                val text = el.select(".tgme_widget_message_text").text().trim()
                if (text.isBlank()) continue
                if (!matchesKeyword(text, keyword)) continue

                val datetime = el.select("time").attr("datetime")
                val postId = el.select(".tgme_widget_message").attr("data-post")
                val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
                    .ifBlank { text.take(80) }

                val pwdFromText = PASSWD_PATTERN.find(text)?.groupValues?.getOrNull(1)
                val seen = LinkedHashSet<String>()
                val links = mutableListOf<Pair<String, NetDiskType>>()

                fun addRaw(raw: String) {
                    resolveShare(raw)?.let { (shareUrl, type) ->
                        val key = shareUrl.lowercase(Locale.ROOT)
                        if (seen.add(key)) links.add(shareUrl to type)
                    }
                }

                URL_PATTERN.findAll(text).forEach { addRaw(it.value) }
                el.select(".tgme_widget_message_text a[href]").forEach { a ->
                    val href = a.attr("href")
                    if (href.isNotBlank()) addRaw(href)
                }

                for ((shareUrl, type) in links) {
                    if (out.size >= limit) break
                    var title = firstLine
                    title = title.replace(shareUrl, "", ignoreCase = true)
                    title = title
                        .replace(
                            Regex("""(名称|描述|链接|大小|标签|夸克|UC|百度|阿里|迅雷|115|天翼|123|移动|提取码|密码|：|,|\.|\||-|\s)+"""),
                            " "
                        )
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                        .take(80)
                    if (title.isBlank()) title = firstLine.take(80)

                    val pwdFromUrl = pwdFromShareUrl(shareUrl)
                    val pwd = pwdFromText ?: pwdFromUrl

                    val id = "tg-$channel-${postId.ifBlank { index.toString() }}-${out.size}"
                    out.add(
                        SearchResult(
                            id = id,
                            title = title,
                            description = text.take(160),
                            url = shareUrl,
                            netDiskType = type,
                            date = datetime.take(19).replace('T', ' '),
                            sourceUrl = "https://t.me/s/$channel",
                            sourceName = "TG@$channel",
                            sourceId = "telegram",
                            category = ResourceCategory.NETDISK,
                            fileType = FileTypes.detectFromTitle(title),
                            extractionCode = pwd
                        )
                    )
                }
            }
            return out
        }

        /**
         * Fallback for r.jina.ai reader/markdown output which usually has no
         * Telegram widget CSS. Treat blank-line separated blocks as messages.
         */
        internal fun parseChannelPlainText(
            body: String,
            channel: String,
            keyword: String,
            limit: Int
        ): List<SearchResult> {
            if (!matchesKeyword(body, keyword)) return emptyList()
            val blocks = body
                .split(Regex("""\n\s*\n"""))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val out = mutableListOf<SearchResult>()
            val globalSeen = LinkedHashSet<String>()
            for ((index, text) in blocks.withIndex()) {
                if (out.size >= limit) break
                if (!matchesKeyword(text, keyword)) continue
                val pwdFromText = PASSWD_PATTERN.find(text)?.groupValues?.getOrNull(1)
                val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
                    .ifBlank { text.take(80) }
                for (m in URL_PATTERN.findAll(text)) {
                    if (out.size >= limit) break
                    val resolved = resolveShare(m.value) ?: continue
                    val (shareUrl, type) = resolved
                    val key = shareUrl.lowercase(Locale.ROOT)
                    if (!globalSeen.add(key)) continue
                    var title = firstLine.replace(shareUrl, "", ignoreCase = true)
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                        .take(80)
                    if (title.isBlank()) title = firstLine.take(80)
                    out.add(
                        SearchResult(
                            id = "tg-$channel-plain-$index-${out.size}",
                            title = title,
                            description = text.take(160),
                            url = shareUrl,
                            netDiskType = type,
                            sourceUrl = "https://t.me/s/$channel",
                            sourceName = "TG@$channel",
                            sourceId = "telegram",
                            category = ResourceCategory.NETDISK,
                            fileType = FileTypes.detectFromTitle(title),
                            extractionCode = pwdFromText ?: pwdFromShareUrl(shareUrl)
                        )
                    )
                }
            }
            return out
        }

        private fun pwdFromShareUrl(shareUrl: String): String? =
            runCatching {
                val uri = java.net.URI(shareUrl)
                uri.query
                    ?.split('&')
                    ?.mapNotNull {
                        val i = it.indexOf('=')
                        if (i <= 0) null
                        else it.substring(0, i).lowercase(Locale.ROOT) to
                            java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
                    }
                    ?.firstOrNull { it.first == "pwd" || it.first == "password" }
                    ?.second
                    ?.takeIf { it.isNotBlank() }
            }.getOrNull()

        private fun matchesKeyword(text: String, keyword: String): Boolean {
            val hay = text.lowercase(Locale.ROOT)
            val tokens = keyword.lowercase(Locale.ROOT)
                .split(' ', '　', '\t')
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return hay.contains(keyword.lowercase(Locale.ROOT))
            return tokens.all { hay.contains(it) }
        }

        private fun resolveShare(raw: String): Pair<String, NetDiskType>? {
            if (raw.startsWith("magnet:", ignoreCase = true)) return null
            val deproxied = deproxy(raw)
            val parsed = runCatching { java.net.URI(deproxied) }.getOrNull() ?: return null
            val type = classifyHost(parsed.host.orEmpty())
            if (type != null) return deproxied to type

            val nested = parsed.query
                ?.split('&')
                ?.mapNotNull {
                    val i = it.indexOf('=')
                    if (i <= 0) null else it.substring(0, i) to
                        java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
                }
                ?.firstOrNull { it.first.equals("url", ignoreCase = true) }
                ?.second
            if (!nested.isNullOrBlank()) {
                val nestedDeproxied = deproxy(nested)
                val nestedHost = runCatching { java.net.URI(nestedDeproxied).host }.getOrNull().orEmpty()
                val nestedType = classifyHost(nestedHost)
                if (nestedType != null) return nestedDeproxied to nestedType
            }
            return null
        }

        private fun deproxy(raw: String): String {
            return try {
                val u = java.net.URI(raw)
                if (u.host.equals("r.jina.ai", ignoreCase = true)) {
                    val path = java.net.URLDecoder.decode(u.path.orEmpty(), "UTF-8")
                    if (path.startsWith("/http://") || path.startsWith("/https://")) {
                        return path.removePrefix("/")
                    }
                }
                raw
            } catch (_: Exception) {
                raw
            }
        }

        private fun classifyHost(hostRaw: String): NetDiskType? {
            val host = hostRaw.lowercase(Locale.ROOT)
            if (host.isBlank() || host == "t.me" || host.endsWith(".t.me") || host == "r.jina.ai") {
                return null
            }
            return when {
                host.endsWith("alipan.com") || host.endsWith("aliyundrive.com") -> NetDiskType.ALIYUN
                host == "pan.baidu.com" -> NetDiskType.BAIDU
                host == "pan.quark.cn" || host == "drive.quark.cn" -> NetDiskType.QUARK
                host == "pan.xunlei.com" -> NetDiskType.XUNLEI
                host.contains("123pan") || host.contains("123684") ||
                    host.contains("123685") || host.contains("123912") -> NetDiskType.YUNPAN123
                else -> null
            }
        }
    }
}
