package com.dashensou.app.service

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.service.source.AiQuSource
import com.dashensou.app.service.source.Api52Source
import com.dashensou.app.service.source.FailureKind
import com.dashensou.app.service.source.GutendexSource
import com.dashensou.app.service.source.OpenLibrarySource
import com.dashensou.app.service.source.PanClubAlipanSource
import com.dashensou.app.service.source.PanClubBaiduSource
import com.dashensou.app.service.source.PanClubQuarkSource
import com.dashensou.app.service.source.PanSouSource
import com.dashensou.app.service.source.PansouCcSource
import com.dashensou.app.service.source.SearchOutcome
import com.dashensou.app.service.source.SearchSource
import com.dashensou.app.service.source.WanzhanApiSource
import com.dashensou.app.service.source.XiaoShuoApiSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class SearchService(
    private val wanzhanApiKeys: List<String> = emptyList(),
    val sources: List<SearchSource> = defaultSources(wanzhanApiKeys)
) {

    companion object {
        private const val TAG = "SearchService"

        private const val SOURCE_TIMEOUT_MS = 2500L
        // P0#perf: hard cap on results per source. A misbehaving or
        // hot-keyword source returning thousands of items used to cost
        // us (a) a dedup pass on the whole list, (b) a sort over the
        // whole list, (c) a long RecyclerView bind. 200 is comfortably
        // above what the user can scan in a single screen and stays
        // snappy under fan-out.
        private const val MAX_RESULTS_PER_SOURCE = 200
        private const val SOURCE_WEIGHT_WANZHAN = 100
        private const val SOURCE_WEIGHT_PANSOU252 = 95
        private const val SOURCE_WEIGHT_API52 = 90
        private const val SOURCE_WEIGHT_PANSOU = 80
        private const val SOURCE_WEIGHT_PANCLUB = 70
        private const val SOURCE_WEIGHT_XIAOSHUO = 60
        private const val SOURCE_WEIGHT_AIQU = 55
        private const val SOURCE_WEIGHT_OPENLIBRARY = 40
        private const val SOURCE_WEIGHT_GUTENDEX = 30

        private const val NETDISK_BAIDU = 30
        private const val NETDISK_QUARK = 28
        private const val NETDISK_ALIYUN = 26
        private const val NETDISK_XUNLEI = 22
        private const val NETDISK_YUNPAN123 = 20
        private const val NETDISK_DIRECT_URL = 10
        private const val NETDISK_OTHER = 0

        private const val FRESHNESS_DAYS_FRESH = 180
        private const val FRESHNESS_DAYS_OK = 730

        // P0#relevance: weights for the new relevance formula. The miss
        // penalty magnitude is intentionally larger than the max base
        // score (source + netDisk + freshness = 100 + 30 + 5 = 135) so
        // a title that does not contain the keyword at all can never
        // be promoted by source/disk bonuses alone.
        private const val MISS_PENALTY = -200.0
        private const val HIT_FULL_BONUS = 150.0
        private const val HIT_TOKEN_BONUS = 35.0
        private const val POSITION_MAX_BONUS = 40.0
        private const val SHORT_TITLE_BONUS = 8.0
        private const val SHORT_TITLE_MAX_CHARS = 8

        fun defaultSources(wanzhanApiKeys: List<String> = emptyList()): List<SearchSource> = listOf(
            WanzhanApiSource(apiKeys = wanzhanApiKeys).apply { enabled = true },
            PanSouSource().apply { enabled = true },
            PansouCcSource().apply { enabled = true },
            PanClubQuarkSource().apply { enabled = true },
            PanClubBaiduSource().apply { enabled = true },
            PanClubAlipanSource().apply { enabled = true },
            XiaoShuoApiSource().apply { enabled = true },
            AiQuSource().apply { enabled = true },
            Api52Source().apply { enabled = false },
            OpenLibrarySource().apply { enabled = false },
            GutendexSource().apply { enabled = false }
        )

        /**
         * Stable source weights. P0#ux: the weight is keyed off the
         * SearchSource.id, never the displayName. Display names are
         * user-facing strings and can be renamed (e.g. for
         * localisation, or to obscure third-party brands from the
         * UI) without affecting relevance scoring.
         */
        private fun sourceWeight(id: String): Int = when (id) {
            "wanzhan" -> SOURCE_WEIGHT_WANZHAN
            "pansou_252" -> SOURCE_WEIGHT_PANSOU252
            "pansou_cc" -> SOURCE_WEIGHT_PANSOU
            "api52" -> SOURCE_WEIGHT_API52
            "panclub_quark",
            "panclub_baidu",
            "panclub_alipan" -> SOURCE_WEIGHT_PANCLUB
            "xiaoshuo" -> SOURCE_WEIGHT_XIAOSHUO
            "aiqu225" -> SOURCE_WEIGHT_AIQU
            "openlibrary" -> SOURCE_WEIGHT_OPENLIBRARY
            "gutendex" -> SOURCE_WEIGHT_GUTENDEX
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

    suspend fun search(
        keyword: String,
        page: Int = 1,
        category: ResourceCategory = ResourceCategory.ALL
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }

        val activeSources = sources.filter { it.enabled }
        Log.i(TAG, "search '$keyword' page=$page category=$category activeSources=${activeSources.size}/${sources.size}")

        val perSource = coroutineScope {
            activeSources.map { source ->
                async(Dispatchers.IO) {
                    val name = source.displayName
                    val start = System.currentTimeMillis()
                    Log.i(TAG, "[START] source '$name' begin")
                    // For the "全部" (ALL) tab we deliberately pass ALL
                    // through; the category filter in every source's
                    // matchesCategory(...) already short-circuits to "true"
                    // when the category is ALL, so we are not asking any
                    // source to drop results. The front-end filterByCategory
                    // also early-returns for ALL. Net effect: ALL tab shows
                    // the unfiltered union of every source.
                    val outcome = try {
                        // A few HTML-rendered aggregators (pan.club mirrors)
                        // declare their own per-source budget. The default
                        // SOURCE_TIMEOUT_MS is right for JSON endpoints.
                        val budget = if (source.perSourceTimeoutMs > 0L)
                            source.perSourceTimeoutMs
                        else
                            SOURCE_TIMEOUT_MS
                        withTimeoutOrNull(budget) {
                            source.search(keyword, page, category)
                        } ?: run {
                            val cost = System.currentTimeMillis() - start
                            Log.w(TAG, "[TIMEOUT] source '$name' cost=${cost}ms (limit=${budget}ms)")
                            // P1#16: keep the kind on the timeout path so
                            // the UI can show a "稍后重试 / 换关键词" hint
                            // instead of pretending the source returned
                            // an empty success.
                            SearchOutcome.Failure.timeout("搜索超时 (>${budget}ms)")
                        }
                    } catch (e: Exception) {
                        val cost = System.currentTimeMillis() - start
                        Log.e(TAG, "[CRASH] source '$name' cost=${cost}ms: ${e.message}", e)
                        SearchOutcome.Failure.parse("异常: ${e.message ?: "未知"}", e)
                    }
                    val cost = System.currentTimeMillis() - start
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
                            Log.w(TAG, "source '$name' returned ${outcome.results.size} > cap $MAX_RESULTS_PER_SOURCE, truncating")
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
        Log.i(TAG, "merged=${merged.size} deduped=${deduped.size} sorted=${sorted.size} failures=${failures.size}")

        when {
            sorted.isNotEmpty() -> SearchOutcome.Success(sorted)
            failures.isNotEmpty() && merged.isEmpty() -> {
                // P1#16: pick the most "telling" kind from the failed
                // sources. NETWORK wins over SOURCE_DOWN (the user is
                // more likely to fix the device than wait for an
                // upstream), TIMEOUT/PARSE bubble up when nothing
                // higher-priority is present, EMPTY is the all-clean
                // case. Unknown is the catch-all.
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
        // User-actionable first: if anything is down at the network
        // level, that dominates whatever the upstream sites are doing.
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
        FailureKind.TIMEOUT -> "搜索超时,可换个关键词再试 (${failedSourceCount} 个源异常)"
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
            .replace("[^a-z0-9\\u4e00-\\u9fa5]".toRegex(), "")
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

    /**
     * Relevance score. P0#relevance: previous formula was "base + bonus
     * if title contains keyword", with NO penalty when the title did NOT
     * contain the keyword at all. Combined with high source weights
     * (Wanzhan = 100, Quark = 28) that meant unrelated results like
     * "逆行人生" or "凡人修仙传" still scored ~135 and floated to the
     * top of a search for "穿越".
     *
     * The new formula has a hard "any-token hit required" floor
     * ([MISS_PENALTY] = -200). If a result title does not contain the
     * search keyword as a substring nor any whitespace-split token, it
     * is effectively demoted to the bottom of the list. Source/disk
     * bonuses (max 135) cannot pull it back up because they are
     * strictly smaller than the penalty magnitude.
     *
     * Token-level bonuses are weighted by hit *ratio* not hit *count*,
     * so a 2-token query with 2/2 hits outscores a 3-token query with
     * 2/3 hits even though both have hit count = 2.
     *
     * Title-position weight: a keyword (or first hit token) found near
     * the start of the title is treated as a stronger signal than one
     * at the tail. "穿越之xxx" outranks "xxx穿越记" because the user
     * is more likely to have searched for the leading topic.
     */
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

        // --- keyword relevance (the part that fixes "inaccurate tail") ---
        if (keywordTokens.isEmpty()) {
            // Single literal token. Fall back to plain substring match.
            val hits = titleLower.contains(keywordLower)
            if (!hits) {
                score += MISS_PENALTY
            } else {
                score += HIT_FULL_BONUS
                score += positionBonus(titleLower, keywordLower)
            }
        } else {
            // Multi-token keyword. Compute per-token hit and ratio.
            val hitCount = keywordTokens.count { titleLower.contains(it) }
            if (hitCount == 0) {
                score += MISS_PENALTY
            } else {
                val ratio = hitCount.toDouble() / keywordTokens.size
                score += HIT_FULL_BONUS * ratio
                score += hitCount * HIT_TOKEN_BONUS
                if (hitCount == keywordTokens.size) {
                    // All tokens hit — strong positive. Position of the
                    // first token also counts here.
                    score += positionBonus(titleLower, keywordTokens[0])
                }
            }
        }

        // --- freshness (capped, very old results stop earning bonus) ---
        val parsedDate = parseDateMillis(r.date)
        if (parsedDate != null) {
            val days = ((nowMs - parsedDate) / 86_400_000L).coerceAtLeast(0)
            score += when {
                days <= FRESHNESS_DAYS_FRESH -> 5.0
                days <= FRESHNESS_DAYS_OK -> 2.0
                else -> 0.0
            }
        }

        // --- short-title bias (light tie-breaker) ---
        // A 4-8 char title is usually closer to a real title than a
        // long descriptive one with the keyword shoehorned in.
        if (titleLower.length in 4..SHORT_TITLE_MAX_CHARS) {
            score += SHORT_TITLE_BONUS
        }

        return score
    }

    /**
     * Where the keyword first appears in the title. A match at the
     * start of the title outranks one at the tail. We use the
     * normalised position (0.0 - 1.0) so this stays a small tie-breaker
     * rather than a dominant signal.
     */
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
