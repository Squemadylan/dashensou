package com.dashensou.app.util

import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.service.source.SearchOutcome
import com.dashensou.app.service.source.SearchSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manual health probe for [SearchSource]s.
 *
 * Probes run in parallel (all enabled sources simultaneously) and each
 * result is emitted via [onResult] as soon as it completes — the UI can
 * display them one by one without waiting for the whole batch.
 *
 * Keyword "三体" is used so backend validators that check for valid
 * Chinese text are satisfied.
 *
 * Lifecycle: [shutdown] cancels the probe scope. One probe at a time.
 */
class SourceHealthChecker(
    private val sources: List<SearchSource>,
    private val probeKeyword: String = "三体",
    private val probeTimeoutMs: Long = 10_000L
) {

    data class Result(
        val sourceId: String,
        val displayName: String,
        val status: Status,
        val latencyMs: Long,
        val message: String = ""
    )

    enum class Status { OK, FAIL, SKIPPED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    /**
     * Fire probes for all enabled sources in parallel. [onResult] is
     * called from a background thread for each source as soon as its
     * result is ready — callers must marshal to the main thread.
     * [onDone] is called once when all probes have completed.
     */
    fun start(
        onResult: (Result) -> Unit,
        onDone: () -> Unit = {}
    ): Job {
        runningJob?.cancel()
        runningJob = scope.launch {
            val enabled = sources.filter { it.enabled }
            if (enabled.isEmpty()) {
                sources.forEach { src ->
                    onResult(Result(src.id, src.displayName, Status.SKIPPED, 0L, "未启用"))
                }
                onDone()
                return@launch
            }

            // Probe all enabled sources concurrently; emit each result
            // as soon as it arrives so the UI updates live.
            enabled.map { src ->
                async {
                    probeOne(src)
                }
            }.forEach { deferred ->
                val result = deferred.await()
                withTimeoutOrNull(0) { onResult(result) }
                    ?: onResult(result) // fallback if marshal window closed
            }
            onDone()
        }
        return runningJob!!
    }

    private suspend fun probeOne(source: SearchSource): Result {
        val start = System.currentTimeMillis()
        val outcome = withTimeoutOrNull(probeTimeoutMs) {
            runCatching { source.search(probeKeyword, 1, ResourceCategory.ALL) }
                .getOrElse { SearchOutcome.Failure.parse("异常: ${it.message ?: "未知"}", it) }
        }
        val cost = System.currentTimeMillis() - start
        return when {
            outcome == null -> Result(
                source.id, source.displayName, Status.FAIL, cost, "超时 (>10秒)"
            )
            outcome is SearchOutcome.Success -> Result(
                source.id, source.displayName, Status.OK, cost
            )
            outcome is SearchOutcome.Failure -> Result(
                source.id, source.displayName, Status.FAIL, cost, outcome.message
            )
            else -> Result(
                source.id, source.displayName, Status.FAIL, cost, "未知结果"
            )
        }
    }

    fun shutdown() {
        runningJob?.cancel()
        runningJob = null
        scope.cancel()
    }
}
