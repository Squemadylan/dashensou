package com.dashensou.app.service

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.service.source.GutendexSource
import com.dashensou.app.service.source.OpenLibrarySource
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

        private const val SOURCE_TIMEOUT_MS = 6000L
        private const val SOURCE_WEIGHT_WANZHAN = 100
        private const val SOURCE_WEIGHT_PANSOU = 80
        private const val SOURCE_WEIGHT_XIAOSHUO = 60
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

        fun defaultSources(wanzhanApiKeys: List<String> = emptyList()): List<SearchSource> = listOf(
            WanzhanApiSource(apiKeys = wanzhanApiKeys),
            PansouCcSource(),
            XiaoShuoApiSource(),
            OpenLibrarySource(),
            GutendexSource()
        )

        private fun sourceWeight(name: String): Int = when {
            name.contains("万站", ignoreCase = true) -> SOURCE_WEIGHT_WANZHAN
            name.contains("Pansou", ignoreCase = true) -> SOURCE_WEIGHT_PANSOU
            name.contains("小说", ignoreCase = true) -> SOURCE_WEIGHT_XIAOSHUO
            name.contains("OpenLibrary", ignoreCase = true) -> SOURCE_WEIGHT_OPENLIBRARY
            name.contains("Gutendex", ignoreCase = true) -> SOURCE_WEIGHT_GUTENDEX
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
                    val outcome = try {
                        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                            source.search(keyword, page, category)
                        } ?: run {
                            Log.w(TAG, "source '$name' timed out after ${SOURCE_TIMEOUT_MS}ms")
                            SearchOutcome.Success(emptyList())
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "source '$name' crashed: ${e.message}", e)
                        SearchOutcome.Failure("异常: ${e.message ?: "未知"}", e)
                    }
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
                        Log.i(TAG, "source '$name' returned ${outcome.results.size}")
                        merged.addAll(outcome.results)
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
                SearchOutcome.Failure("全部数据源失败：${failures.joinToString("; ")}")
            }
            else -> SearchOutcome.Success(emptyList())
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
        val primary = group.maxBy { sourceWeight(it.sourceName) }
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
        return list.sortedByDescending { r -> scoreOf(r, kwLower, now) }
    }

    private fun scoreOf(r: SearchResult, keywordLower: String, nowMs: Long): Double {
        var score = 0.0
        score += sourceWeight(r.sourceName)
        score += netDiskWeight(r.netDiskType)

        val titleLower = r.title.lowercase(Locale.ROOT)
        if (titleLower.contains(keywordLower)) score += 25
        if (titleLower.startsWith(keywordLower)) score += 10
        if (r.extractionCode.isNullOrBlank()) score += 2

        val parsedDate = parseDateMillis(r.date)
        if (parsedDate != null) {
            val days = ((nowMs - parsedDate) / 86_400_000L).coerceAtLeast(0)
            score += when {
                days <= FRESHNESS_DAYS_FRESH -> 10.0
                days <= FRESHNESS_DAYS_OK -> 5.0
                days <= FRESHNESS_DAYS_OK * 2 -> 1.0
                else -> -5.0
            }
        }

        return score
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
