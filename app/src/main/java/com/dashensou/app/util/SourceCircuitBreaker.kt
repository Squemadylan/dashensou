package com.dashensou.app.util

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-source circuit breaker inspired by PanHub's pluginHealth.
 *
 * After [maxFailures] consecutive failures (timeout / network / source-down),
 * the source is skipped for [cooldownMs]. Success while open after cooldown
 * resets the counter. Unknown sources are treated as healthy.
 */
class SourceCircuitBreaker(
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS
) {
    data class Status(
        val sourceId: String,
        val healthy: Boolean,
        val failureCount: Int,
        val successCount: Int,
        val lastFailureAt: Long?,
        val avgResponseMs: Long
    )

    private data class Entry(
        var healthy: Boolean = true,
        var failureCount: Int = 0,
        var successCount: Int = 0,
        var lastFailureAt: Long? = null,
        var lastSuccessAt: Long? = null,
        var avgResponseMs: Long = 0L
    )

    private val map = ConcurrentHashMap<String, Entry>()

    fun isHealthy(sourceId: String): Boolean {
        val entry = map[sourceId] ?: return true
        if (entry.healthy) return true
        val lastFail = entry.lastFailureAt ?: return true
        val elapsed = System.currentTimeMillis() - lastFail
        if (elapsed > cooldownMs) {
            entry.healthy = true
            entry.failureCount = 0
            Log.i(TAG, "circuit half-open recover: $sourceId after ${elapsed}ms")
            return true
        }
        return false
    }

    fun recordSuccess(sourceId: String, responseMs: Long) {
        val entry = map.getOrPut(sourceId) { Entry() }
        synchronized(entry) {
            entry.successCount++
            entry.lastSuccessAt = System.currentTimeMillis()
            val n = entry.successCount
            entry.avgResponseMs = if (n <= 1) {
                responseMs
            } else {
                (entry.avgResponseMs * (n - 1) + responseMs) / n
            }
            if (!entry.healthy) {
                val lastFail = entry.lastFailureAt
                if (lastFail == null || System.currentTimeMillis() - lastFail > cooldownMs) {
                    entry.healthy = true
                    entry.failureCount = 0
                    Log.i(TAG, "circuit closed after success: $sourceId")
                }
            } else {
                entry.failureCount = 0
            }
        }
    }

    fun recordFailure(sourceId: String) {
        val entry = map.getOrPut(sourceId) { Entry() }
        synchronized(entry) {
            entry.failureCount++
            entry.lastFailureAt = System.currentTimeMillis()
            if (entry.healthy && entry.failureCount >= maxFailures) {
                entry.healthy = false
                Log.w(
                    TAG,
                    "circuit OPEN: $sourceId failures=${entry.failureCount} " +
                        "cooldown=${cooldownMs}ms"
                )
            }
        }
    }

    fun status(sourceId: String): Status {
        val entry = map[sourceId] ?: return Status(sourceId, true, 0, 0, null, 0)
        return Status(
            sourceId = sourceId,
            healthy = isHealthy(sourceId),
            failureCount = entry.failureCount,
            successCount = entry.successCount,
            lastFailureAt = entry.lastFailureAt,
            avgResponseMs = entry.avgResponseMs
        )
    }

    fun reset(sourceId: String? = null) {
        if (sourceId == null) map.clear() else map.remove(sourceId)
    }

    companion object {
        private const val TAG = "SourceCircuitBreaker"
        const val DEFAULT_MAX_FAILURES = 3
        const val DEFAULT_COOLDOWN_MS = 5 * 60 * 1000L
    }
}
