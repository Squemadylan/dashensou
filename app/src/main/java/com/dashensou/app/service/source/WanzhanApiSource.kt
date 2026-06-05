package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import com.dashensou.app.util.CategoryRules
import com.dashensou.app.util.FileTypes
import com.dashensou.app.util.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class WanzhanApiSource(
    private val apiKeys: List<String> = emptyList()
) : SearchSource {

    override val id = "wanzhan"
    override val displayName = "万站聚合"
    override var enabled: Boolean = true
    override val perSourceTimeoutMs: Long = API_BUDGET_MS

    private data class KeyHealth(
        var consecutiveFailures: Int = 0,
        var cooldownUntilMs: Long = 0L
    )

    private val keyHealthMap = ConcurrentHashMap<String, KeyHealth>()
    // P0#robustness: every read+mutate pair on a KeyHealth instance is
    // guarded by this monitor. The previous design read & wrote the two
    // fields directly from multiple search() coroutines, which meant
    // a "failure++" could race with a "read for cooldown" and the
    // counter could get lost. The map itself is concurrent so the
    // getOrPut side is safe; we only need to protect the fields.
    private val healthLock = Any()

    companion object {
        private const val TAG = "WanzhanApiSource"
        private const val BASE_URL = "https://wzapi.com/api/jhsj"

        private const val MAX_KEY_FAILURES = 2
        private const val KEY_COOLDOWN_MS = 120_000L
        private const val MAX_TOTAL_RETRIES = 3
        private const val BACKOFF_BASE_MS = 500L
        private const val BACKOFF_MAX_MS = 2_000L

        private const val GLOBAL_MIN_INTERVAL_MS = 4000L
        private const val API_BUDGET_MS = 12_000L

        private val apiClient: OkHttpClient by lazy {
            HttpClient.client.newBuilder()
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .callTimeout(API_BUDGET_MS, TimeUnit.MILLISECONDS)
                .build()
        }

        @Volatile
        private var lastSuccessTimestamp: Long = 0L
    }

    private fun healthOf(key: String): KeyHealth =
        keyHealthMap.getOrPut(key) { KeyHealth() }

    private fun isKeyAvailable(key: String, now: Long): Boolean {
        val h = healthOf(key)
        return synchronized(healthLock) {
            h.consecutiveFailures < MAX_KEY_FAILURES || now >= h.cooldownUntilMs
        }
    }

    private fun markKeySuccess(key: String) {
        val h = healthOf(key)
        synchronized(healthLock) {
            h.consecutiveFailures = 0
            h.cooldownUntilMs = 0L
        }
        Log.d(TAG, "key '${key.take(6)}...' success, reset health")
    }

    private fun markKeyFailure(key: String) {
        val h = healthOf(key)
        val now = System.currentTimeMillis()
        val (failures, cooldown) = synchronized(healthLock) {
            h.consecutiveFailures += 1
            if (h.consecutiveFailures >= MAX_KEY_FAILURES) {
                h.cooldownUntilMs = now + KEY_COOLDOWN_MS
            }
            h.consecutiveFailures to h.cooldownUntilMs
        }
        if (cooldown > now) {
            Log.w(TAG, "key '${key.take(6)}...' disabled for ${KEY_COOLDOWN_MS}ms (failures=$failures)")
        } else {
            Log.w(TAG, "key '${key.take(6)}...' failure $failures/$MAX_KEY_FAILURES")
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val exp = (1L shl attempt.coerceIn(0, 6)).coerceAtMost(BACKOFF_MAX_MS / BACKOFF_BASE_MS)
        val base = BACKOFF_BASE_MS * exp
        val jitter = (Math.random() * (base / 4 + 1)).toLong()
        return (base + jitter).coerceAtMost(BACKOFF_MAX_MS)
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome = withContext(Dispatchers.IO) {
        if (keyword.isBlank()) {
            return@withContext SearchOutcome.Success(emptyList())
        }

        val now = System.currentTimeMillis()
        val elapsed = now - lastSuccessTimestamp
        if (lastSuccessTimestamp > 0 && elapsed < GLOBAL_MIN_INTERVAL_MS) {
            val wait = GLOBAL_MIN_INTERVAL_MS - elapsed
            Log.d(TAG, "global rate-limit, wait ${wait}ms (elapsed=${elapsed}ms)")
            delay(wait)
        }

        val keysToTry = if (apiKeys.isEmpty()) {
            listOf<String?>(null)
        } else {
            val ordered = apiKeys.sortedBy { if (isKeyAvailable(it, now)) 0 else 1 }
            val available = ordered.filter { isKeyAvailable(it, now) }
            if (available.isEmpty()) ordered else available
        }.let { keys ->
            if (keys.isNotEmpty() && keys.first() != null) keys + listOf(null) else keys
        }

        var lastError: String? = null
        var totalAttempts = 0

        for ((keyIdx, key) in keysToTry.withIndex()) {
            if (totalAttempts >= MAX_TOTAL_RETRIES) break
            totalAttempts++

            if (keyIdx > 0) {
                val wait = backoffMs(keyIdx)
                Log.d(TAG, "backoff ${wait}ms before key #${keyIdx + 1}")
                delay(wait)
            }

            val outcome = doRequest(keyword, page, category, key)
            when (outcome) {
                is AttemptResult.Success -> {
                    if (key != null) markKeySuccess(key)
                    lastSuccessTimestamp = System.currentTimeMillis()
                    return@withContext SearchOutcome.Success(outcome.results)
                }
                is AttemptResult.AuthError -> {
                    if (key != null) markKeyFailure(key)
                    lastError = outcome.message
                    Log.w(TAG, "auth/quota error, try next key: ${outcome.message}")
                }
                is AttemptResult.NetworkError -> {
                    if (key != null) markKeyFailure(key)
                    lastError = outcome.message
                    Log.w(TAG, "network error, try next key: ${outcome.message}")
                }
                is AttemptResult.ParseError -> {
                    Log.w(TAG, "parse error: ${outcome.message}")
                    return@withContext SearchOutcome.Failure.parse(outcome.message, outcome.cause)
                }
            }
        }

        SearchOutcome.Failure.sourceDown(lastError ?: "所有重试均失败")
    }

    private sealed class AttemptResult {
        data class Success(val results: List<SearchResult>) : AttemptResult()
        data class AuthError(val message: String) : AttemptResult()
        data class NetworkError(val message: String) : AttemptResult()
        data class ParseError(val message: String, val cause: Throwable?) : AttemptResult()
    }

    private fun doRequest(keyword: String, page: Int, category: ResourceCategory, apiKey: String?): AttemptResult {
        val urlBuilder = BASE_URL.toHttpUrl().newBuilder()
            .addQueryParameter("kw", keyword.trim())
            .addQueryParameter("page", page.toString())
        if (apiKey != null) {
            urlBuilder.addQueryParameter("apiKey", apiKey)
        }
        val url = urlBuilder.build().toString()

        return try {
            val request = HttpClient.newGet(url)
            // We can't route through HttpClient.getString here because
            // we need HTTP status code for the 401/403/429 -> AuthError
            // path (HttpClient swallows the code). So we keep the raw
            // OkHttp call local to this one method.
            apiClient.newCall(request).execute().use { response ->
                Log.d(TAG, "response: code=${response.code} bodyLength=${response.body?.contentLength() ?: -1}")

                if (response.code == 429) {
                    return AttemptResult.AuthError("万站API请求过于频繁(限流),请稍后再试")
                }
                if (response.code == 401 || response.code == 403) {
                    return AttemptResult.AuthError("万站API鉴权失败(HTTP ${response.code})")
                }
                if (!response.isSuccessful) {
                    return AttemptResult.AuthError("HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: return AttemptResult.ParseError("响应体为空", null)

                val root = try {
                    Json.parseObject(body)
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parse failed: ${body.take(200)}", e)
                    return AttemptResult.ParseError("JSON 解析失败", e)
                }

                val code = root.optInt("code", -1)
                if (code != 0) {
                    val msg = root.optString("message", "未知错误")
                    val isAuth = code == 401 || code == 403 ||
                        msg.contains("key", ignoreCase = true) ||
                        msg.contains("limit", ignoreCase = true) ||
                        msg.contains("quota", ignoreCase = true)
                    if (isAuth) {
                        return AttemptResult.AuthError("API: $msg")
                    }
                    return AttemptResult.ParseError("API: $msg", null)
                }

                val data = root.optJSONObject("data")
                val merged = data?.optJSONObject("merged_by_type")
                if (data == null || merged == null) {
                    return AttemptResult.Success(emptyList())
                }

                val results = mutableListOf<SearchResult>()
                val keys = merged.keys()
                while (keys.hasNext()) {
                    val type = keys.next()
                    val arr = merged.optJSONArray(type) ?: continue
                    val netDisk = mapCloudType(type)
                    val len = arr.length()
                    for (i in 0 until len) {
                        val item = arr.optJSONObject(i) ?: continue
                        val urlStr = item.optString("url", "")
                        if (urlStr.isBlank()) continue
                        val title = item.optString("title", "")
                        val note = item.optString("note", "")
                        val effectiveTitle = if (title.isNotBlank()) title else (if (note.isBlank()) type else note)
                        val source = item.optString("source", "")
                        val pwd = item.optString("password", "")
                        val date = item.optString("datetime", "")
                        val finalDate = if (date.isBlank() || date == "0001-01-01T00:00:00Z") "" else date
                        val fullTitle = if (source.isBlank()) effectiveTitle else "$effectiveTitle · $source"
                        val fileType = FileTypes.detectFromTitle(fullTitle)
                        if (!CategoryRules.matchesByNetDisk(netDisk, fileType, category)) continue
                        results.add(
                            SearchResult(
                                id = "wanzhan-$type-$i-${urlStr.hashCode()}",
                                title = fullTitle,
                                description = "${type.uppercase()} · $source",
                                url = urlStr,
                                netDiskType = netDisk,
                                size = "",
                                date = finalDate,
                                sourceUrl = urlStr,
                                sourceName = displayName,
                                sourceId = id,
                                category = category,
                                fileType = fileType,
                                isValid = true,
                                requiresWebView = false,
                                extractionCode = pwd.ifBlank { null }
                            )
                        )
                    }
                }
                Log.d(TAG, "parsed: count=${results.size} (from types: ${merged.length()})")
                AttemptResult.Success(results)
            }
        } catch (e: IOException) {
            AttemptResult.NetworkError("网络异常: ${e.message ?: "未知"}")
        } catch (e: Exception) {
            AttemptResult.ParseError("解析失败: ${e.message ?: "未知"}", e)
        }
    }

    private fun mapCloudType(type: String): NetDiskType = when (type.lowercase()) {
        "baidu" -> NetDiskType.BAIDU
        "quark" -> NetDiskType.QUARK
        "xunlei" -> NetDiskType.XUNLEI
        "aliyun", "aliyunpan" -> NetDiskType.ALIYUN
        "123", "123pan" -> NetDiskType.YUNPAN123
        else -> NetDiskType.OTHER
    }
}
