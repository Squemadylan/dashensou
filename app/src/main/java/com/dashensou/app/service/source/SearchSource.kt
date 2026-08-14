package com.dashensou.app.service.source

import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult

/**
 * Coarse category for a per-source failure. P1#16: the old code bundled
 * every failure into "X 个源异常" and gave the user no way to tell
 * whether their network was down or whether a particular aggregator
 * was rate-limiting them. The UI now shows a context-appropriate
 * suggestion per kind:
 *   NETWORK     -> "检查网络"
 *   TIMEOUT     -> "换关键词 / 稍后重试"
 *   SOURCE_DOWN -> "稍后重试"
 *   PARSE       -> "搜索结果异常,稍后重试"
 *   EMPTY       -> "换关键词再试"
 *   UNKNOWN     -> generic fallback
 */
enum class FailureKind {
    NETWORK,
    TIMEOUT,
    SOURCE_DOWN,
    PARSE,
    EMPTY,
    UNKNOWN
}

sealed class SearchOutcome {
    data class Success(val results: List<SearchResult>) : SearchOutcome()
    data class Failure(
        val message: String,
        val kind: FailureKind = FailureKind.UNKNOWN,
        val cause: Throwable? = null
    ) : SearchOutcome() {

        companion object {
            /** Network-level errors (no DNS, connection refused, TLS, read timeouts, etc). */
            fun network(message: String, cause: Throwable? = null) =
                Failure(message, FailureKind.NETWORK, cause)

            /** Per-source timeout: the coroutine ran past SOURCE_TIMEOUT_MS with no reply. */
            fun timeout(message: String) =
                Failure(message, FailureKind.TIMEOUT)

            /** HTTP 4xx/5xx, rate limits (429), auth failures (401/403), upstream 5xx. */
            fun sourceDown(message: String) =
                Failure(message, FailureKind.SOURCE_DOWN)

            /** Body came back but the parser couldn't make sense of it. */
            fun parse(message: String, cause: Throwable? = null) =
                Failure(message, FailureKind.PARSE, cause)

            /** All sources returned successfully but no rows matched. */
            fun empty(message: String) =
                Failure(message, FailureKind.EMPTY)
        }
    }
}

interface SearchSource {
    val id: String
    val displayName: String
    var enabled: Boolean

    /**
     * Optional per-source override of the single-source timeout budget
     * (defaults to [SearchService.SOURCE_TIMEOUT_MS]). A few HTML-rendered
     * aggregators need more headroom than JSON endpoints because they
     * pay a follow-up detail fetch per card. The override has to be >0;
     * the SearchService wrapper still applies it as a hard ceiling.
     */
    val perSourceTimeoutMs: Long get() = 0L

    suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome
}
