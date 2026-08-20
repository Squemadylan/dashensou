package com.dashensou.app.service

import android.content.Context
import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.service.source.AiQuSource
import com.dashensou.app.service.source.Api52Source
import com.dashensou.app.service.source.FailureKind
import com.dashensou.app.service.source.DuanJuSource
import com.dashensou.app.service.source.GutendexSource
import com.dashensou.app.service.source.HaiSouSource
import com.dashensou.app.service.source.KksoSource
import com.dashensou.app.service.source.OpenLibrarySource
import com.dashensou.app.service.source.PanSouSource
import com.dashensou.app.service.source.PansouCcSource
import com.dashensou.app.service.source.PansouDeSource
import com.dashensou.app.service.source.Quark4kSource
import com.dashensou.app.service.source.SearchOutcome
import com.dashensou.app.service.source.SearchSource
import com.dashensou.app.service.source.TelegramChannelSource
import com.dashensou.app.service.source.WanzhanApiSource
import com.dashensou.app.service.source.XiaoShuoApiSource
import com.dashensou.app.service.source.YunsoSource
import com.dashensou.app.service.source.U3c3Source
import com.dashensou.app.service.source.web.PansouCcWebSource
import com.dashensou.app.util.SourceCircuitBreaker
import com.dashensou.app.util.SourcePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import kotlin.coroutines.coroutineContext

class SearchService(
    context: Context,
    private val wanzhanApiKeys: List<String> = emptyList(),
    private val sourcesList: List<SearchSource> = defaultSources(wanzhanApiKeys)
) {
    val sources: List<SearchSource> get() = sourcesList
    val circuitBreaker = SourceCircuitBreaker()

    companion object {
        private const val TAG = "SearchService"

        private const val SOURCE_TIMEOUT_MS = 2500L
        private const val MAX_RESULTS_PER_SOURCE = 200
        private const val SOURCE_WEIGHT_WANZHAN = 100
        private const val SOURCE_WEIGHT_PANSOU252 = 95
        private const val SOURCE_WEIGHT_API52 = 90
        private const val SOURCE_WEIGHT_PANSOU = 85
        private const val SOURCE_WEIGHT_PANSOU_DE = 86
        private const val SOURCE_WEIGHT_KKSO = 84
        private const val SOURCE_WEIGHT_TELEGRAM = 88
        private const val SOURCE_WEIGHT_XIAOSHUO = 60
        private const val SOURCE_WEIGHT_AIQU = 55
        private const val SOURCE_WEIGHT_OPENLIBRARY = 40
        private const val SOURCE_WEIGHT_GUTENDEX = 30
        private const val SOURCE_WEIGHT_QUARK4K = 50
        private const val SOURCE_WEIGHT_YUNSO = 50
        private const val SOURCE_WEIGHT_U3C3 = 30

        private const val NETDISK_BAIDU = 30
        private const val NETDISK_QUARK = 28
        private const val NETDISK_ALIYUN = 26
        private const val NETDISK_XUNLEI = 22
        private const val NETDISK_YUNPAN123 = 20
        private const val NETDISK_DIRECT_URL = 10
        private const val NETDISK_OTHER = 0

        private const val FRESHNESS_DAYS_FRESH = 180
        private const val FRESHNESS_DAYS_OK = 730

        private const val MISS_PENALTY = -200.0
        private const val HIT_FULL_BONUS = 150.0
        private const val HIT_TOKEN_BONUS = 35.0
        private const val POSITION_MAX_BONUS = 40.0
        private const val SHORT_TITLE_BONUS = 8.0
        private const val SHORT_TITLE_MAX_CHARS = 8

        fun defaultSources(wanzhanApiKeys: List<String> = emptyList()): List<SearchSource> = listOf(
            WanzhanApiSource(apiKeys = wanzhanApiKeys).apply { enabled = true },
            PanSouSource().apply { enabled = true },
            // WebView variant — primary path for HTML-anti-scraping sites
            // (Cloudflare challenge on pansou.cc). It shares one WebView via
            // AppWebView, serialized by an internal Mutex. The OkHttp
            // fallback stays registered but defaults to enabled=false so
            // users can opt-in via the "我的" source-toggle page when the
            // WebView path misbehaves (stale System WebView, hardware quirks).
            // NOTE: aiqu225.com (GBK) and haisou.cc (v2 JSON API) both use
            // plain OkHttp sources (AiQuSource / HaiSouSource) — no WebView.
            PansouCcWebSource().apply { enabled = true },
            // OkHttp variants. aiqu225 (GBK) and haisou (JSON API) are the
            // primary paths for their sites, so they default to on; the
            // pansou.cc OkHttp fallback defaults to off.
            PansouCcSource().apply { enabled = false },
            HaiSouSource().apply { enabled = true },
            // awesome-zhuiju-free cloud_search candidates (kkso / pansou.de).
            // zhuiju.us / gugeso.com expose only encrypted SSE paths — skipped.
            KksoSource().apply { enabled = true },
            PansouDeSource().apply { enabled = true },
            TelegramChannelSource().apply { enabled = true },
            AiQuSource().apply { enabled = true },
            DuanJuSource().apply { enabled = true },
            XiaoShuoApiSource().apply { enabled = true },
            Api52Source().apply { enabled = false },
            Quark4kSource().apply { enabled = true },
            YunsoSource().apply { enabled = true },
            U3c3Source().apply { enabled = false },  // 磁力源，用户按需开启
            OpenLibrarySource().apply { enabled = false },
            GutendexSource().apply { enabled = false }
        )

        private fun sourceWeight(id: String): Int = when (id) {
            "wanzhan" -> SOURCE_WEIGHT_WANZHAN
            "pansou_252" -> SOURCE_WEIGHT_PANSOU252
            "pansou_cc" -> SOURCE_WEIGHT_PANSOU
            "pansou_cc-web" -> SOURCE_WEIGHT_PANSOU
            "haisou" -> SOURCE_WEIGHT_PANSOU
            "pansou_de" -> SOURCE_WEIGHT_PANSOU_DE
            "kkso" -> SOURCE_WEIGHT_KKSO
            "telegram" -> SOURCE_WEIGHT_TELEGRAM
            "duanju" -> SOURCE_WEIGHT_PANSOU252
            "api52" -> SOURCE_WEIGHT_API52
            "xiaoshuo" -> SOURCE_WEIGHT_XIAOSHUO
            "aiqu225" -> SOURCE_WEIGHT_AIQU
            "openlibrary" -> SOURCE_WEIGHT_OPENLIBRARY
            "gutendex" -> SOURCE_WEIGHT_GUTENDEX
            "quark4k" -> SOURCE_WEIGHT_QUARK4K
            "yunso" -> SOURCE_WEIGHT_YUNSO
            "u3c3" -> SOURCE_WEIGHT_U3C3
            else -> 10
        }

        private fun netDiskWeight(type: NetDiskType): Int = when (type) {
            NetDiskType.BAIDU -> NETDISK_BAIDU
            NetDiskType.QUARK -> NETDISK_QUARK
            NetDiskType.ALIYUN -> NETDISK_ALIYUN
            NetDiskType.XUNLEI -> NETDISK_XUNLEI
            NetDiskType.YUNPAN123 -> NETDISK_YUNPAN123
            NetDiskType.DIRECT_URL -> NETDISK_DIRECT_URL
            NetDiskType.OTHER -> NETDISK_OTHER
        }
    }

    init {
        // CRITICAL: load source enabled states from SharedPreferences
        // IMMEDIATELY on creation. SearchService is now a process-wide
        // singleton held by the Application class, so theme-switch
        // Activity recreation never touches it — the sources list and
        // their enabled flags survive any config change.
        SourcePrefs.applyTo(context.applicationContext, sourcesList)
    }

    suspend fun search(
        keyword: String,
        page: Int = 1,
        category: ResourceCategory = ResourceCategory.ALL
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }

        val enabledSources = sources.filter { it.enabled }
        val skipped = enabledSources.filter { !circuitBreaker.isHealthy(it.id) }
        val activeSources = enabledSources.filter { circuitBreaker.isHealthy(it.id) }
        if (skipped.isNotEmpty()) {
            Log.i(
                TAG,
                "circuit skip: ${skipped.joinToString { it.id }}"
            )
        }
        Log.i(
            TAG,
            "search '$keyword' page=$page category=$category " +
                "active=${activeSources.size}/${sources.size} skipped=${skipped.size}"
        )

        if (activeSources.isEmpty()) {
            return@withContext if (skipped.isNotEmpty()) {
                SearchOutcome.Failure.sourceDown(
                    "数据源熔断冷却中,请稍后再试 (${skipped.size} 个源)"
                )
            } else {
                SearchOutcome.Success(emptyList())
            }
        }

        val perSource = coroutineScope {
            activeSources.map { source ->
                async(Dispatchers.IO) {
                    val name = source.displayName
                    val start = System.currentTimeMillis()
                    Log.i(TAG, "[START] source '$name' begin")
                    val outcome = try {
                        val budget = if (source.perSourceTimeoutMs > 0L) {
                            source.perSourceTimeoutMs
                        } else {
                            SOURCE_TIMEOUT_MS
                        }
                        // withTimeout cancels the child coroutine on expiry.
                        // Sources that use HttpClient.execute will abort the
                        // OkHttp Call via invokeOnCancellation — true cancel,
                        // not just discarding a late result.
                        withTimeout(budget) {
                            coroutineContext.ensureActive()
                            source.search(keyword, page, category)
                        }
                    } catch (e: TimeoutCancellationException) {
                        val cost = System.currentTimeMillis() - start
                        Log.w(TAG, "[TIMEOUT] source '$name' cost=${cost}ms")
                        SearchOutcome.Failure.timeout("搜索超时")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val cost = System.currentTimeMillis() - start
                        Log.e(TAG, "[CRASH] source '$name' cost=${cost}ms: ${e.message}", e)
                        SearchOutcome.Failure.parse("异常: ${e.message ?: "未知"}", e)
                    }
                    val cost = System.currentTimeMillis() - start
                    when (outcome) {
                        is SearchOutcome.Success ->
                            circuitBreaker.recordSuccess(source.id, cost)
                        is SearchOutcome.Failure -> when (outcome.kind) {
                            FailureKind.TIMEOUT,
                            FailureKind.NETWORK,
                            FailureKind.SOURCE_DOWN ->
                                circuitBreaker.recordFailure(source.id)
                            else -> Unit
                        }
                    }
                    val count = (outcome as? SearchOutcome.Success)?.results?.size ?: 0
                    Log.i(TAG, "[DONE] source '$name' cost=${cost}ms results=$count")
                    name to outcome
                }
            }.map { it.await() }
        }

        val merged = mutableListOf<SearchResult>()
        val failures = mutableListOf<String>()
        perSource.forEach { (name, outcome) ->
            when (outcome) {
                is SearchOutcome.Success -> {
                    if (outcome.results.isNotEmpty()) {
                        val capped = if (outcome.results.size > MAX_RESULTS_PER_SOURCE) {
                            Log.w(
                                TAG,
                                "source '$name' returned ${outcome.results.size} > " +
                                    "cap $MAX_RESULTS_PER_SOURCE, truncating"
                            )
                            outcome.results.take(MAX_RESULTS_PER_SOURCE)
                        } else {
                            outcome.results
                        }
                        Log.i(TAG, "source '$name' returned ${capped.size}")
                        merged.addAll(capped)
                    } else {
                        Log.d(TAG, "source '$name' returned 0 results")
                    }
                }
                is SearchOutcome.Failure -> {
                    Log.w(TAG, "source '$name' failed: ${outcome.message}")
                    failures.add("$name: ${outcome.message}")
                }
            }
        }

        val deduped = dedupe(merged)
        val sorted = sortByScore(deduped, keyword)
        Log.i(
            TAG,
            "merged=${merged.size} deduped=${deduped.size} " +
                "sorted=${sorted.size} failures=${failures.size}"
        )

        when {
            sorted.isNotEmpty() -> SearchOutcome.Success(sorted)
            failures.isNotEmpty() && merged.isEmpty() -> {
                val pickedKind = pickFailureKind(perSource)
                val hint = kindHint(pickedKind, failures.size, failures)
                SearchOutcome.Failure(hint, pickedKind)
            }
            activeSources.isNotEmpty() -> SearchOutcome.Failure.empty("未找到匹配的资源")
            else -> SearchOutcome.Success(emptyList())
        }
    }

    private fun pickFailureKind(perSource: List<Pair<String, SearchOutcome>>): FailureKind =
        perSource
            .mapNotNull { (_, outcome) ->
                if (outcome is SearchOutcome.Failure) outcome.kind else null
            }
            .maxByOrNull { priority(it) }
            ?: FailureKind.UNKNOWN

    private fun priority(kind: FailureKind): Int = when (kind) {
        FailureKind.NETWORK -> 5
        FailureKind.TIMEOUT -> 4
        FailureKind.SOURCE_DOWN -> 3
        FailureKind.PARSE -> 2
        FailureKind.EMPTY -> 1
        FailureKind.UNKNOWN -> 0
    }

    private fun kindHint(
        kind: FailureKind,
        failedSourceCount: Int,
        failures: List<String> = emptyList()
    ): String {
        val rateLimited = failures.any { msg ->
            msg.contains("429") || msg.contains("限流")
        }
        if (rateLimited) {
            return "部分数据源请求过于频繁(限流),请稍后再试 (${failedSourceCount} 个源异常)"
        }
        return when (kind) {
            FailureKind.NETWORK -> "网络好像不通,检查 WiFi / 数据连接后再试 (${failedSourceCount} 个源异常)"
            FailureKind.TIMEOUT ->
                "搜索超时,可换个关键词或到「我的」检查源开关 (${failedSourceCount} 个源异常)"
            FailureKind.SOURCE_DOWN -> "部分源暂时不可用,稍后重试 (${failedSourceCount} 个源异常)"
            FailureKind.PARSE -> "搜索结果异常,稍后重试 (${failedSourceCount} 个源异常)"
            FailureKind.EMPTY -> "换个关键词再试 (${failedSourceCount} 个源异常)"
            FailureKind.UNKNOWN -> "本次未找到结果,可换个关键词再试 (${failedSourceCount} 个源异常)"
        }
    }

    private fun dedupe(list: List<SearchResult>): List<SearchResult> {
        val groups = LinkedHashMap<String, MutableList<SearchResult>>()
        for (item in list) {
            val key = buildDedupeKey(item)
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }
        return groups.values.map { mergeGroup(it) }
    }

    private fun buildDedupeKey(item: SearchResult): String {
        val normalizedUrl = item.url.substringBefore('?').lowercase(Locale.ROOT)
        val normalizedTitle = item.title.lowercase(Locale.ROOT)
            .replace("[^a-z0-9${'\u4e00'}-${'\u9fa5'}]".toRegex(), "")
            .take(40)
        val type = item.netDiskType.name
        return "$type|$normalizedTitle|$normalizedUrl"
    }

    private fun mergeGroup(group: List<SearchResult>): SearchResult {
        if (group.size == 1) return group[0]
        val primary = group.maxBy { sourceWeight(it.sourceId) }
        val extCodes = group.mapNotNull { it.extractionCode }
            .filter { it.isNotBlank() }
            .distinct()
        val merged = primary.copy(
            extractionCode = primary.extractionCode ?: extCodes.firstOrNull()
        )
        return merged
    }

    private fun sortByScore(list: List<SearchResult>, keyword: String): List<SearchResult> {
        val now = System.currentTimeMillis()
        val kwLower = keyword.lowercase(Locale.ROOT)
        val tokens = kwLower.split(' ', '　', '\t').filter { it.isNotBlank() }
        return list.sortedByDescending { r -> scoreOf(r, kwLower, tokens, now) }
    }

    private fun scoreOf(
        r: SearchResult,
        keywordLower: String,
        keywordTokens: List<String>,
        nowMs: Long
    ): Double {
        var score = 0.0
        score += sourceWeight(r.sourceId)
        score += netDiskWeight(r.netDiskType)

        val titleLower = r.title.lowercase(Locale.ROOT)
        if (r.extractionCode.isNullOrBlank()) score += 2

        if (keywordTokens.isEmpty()) {
            val hits = titleLower.contains(keywordLower)
            if (!hits) {
                score += MISS_PENALTY
            } else {
                score += HIT_FULL_BONUS
                score += positionBonus(titleLower, keywordLower)
            }
        } else {
            val hitCount = keywordTokens.count { titleLower.contains(it) }
            if (hitCount == 0) {
                score += MISS_PENALTY
            } else {
                val ratio = hitCount.toDouble() / keywordTokens.size
                score += HIT_FULL_BONUS * ratio
                score += hitCount * HIT_TOKEN_BONUS
                if (hitCount == keywordTokens.size) {
                    score += positionBonus(titleLower, keywordTokens[0])
                }
            }
        }

        val parsedDate = parseDateMillis(r.date)
        if (parsedDate != null) {
            val days = ((nowMs - parsedDate) / 86_400_000L).coerceAtLeast(0)
            score += when {
                days <= FRESHNESS_DAYS_FRESH -> 5.0
                days <= FRESHNESS_DAYS_OK -> 2.0
                else -> 0.0
            }
        }

        if (titleLower.length in 4..SHORT_TITLE_MAX_CHARS) {
            score += SHORT_TITLE_BONUS
        }

        return score
    }

    private fun positionBonus(titleLower: String, keywordLower: String): Double {
        if (keywordLower.isBlank() || titleLower.isBlank()) return 0.0
        val idx = titleLower.indexOf(keywordLower)
        if (idx < 0) return 0.0
        val norm = idx.toDouble() / titleLower.length
        return POSITION_MAX_BONUS * (1.0 - norm)
    }

    private fun parseDateMillis(s: String): Long? {
        if (s.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
            "yyyy/MM/dd"
        )
        for (p in patterns) {
            try {
                val sdf = java.text.SimpleDateFormat(p, Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                return sdf.parse(s)?.time
            } catch (_: Exception) {
            }
        }
        return null
    }
}
