package com.dashensou.app.service.source

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WanzhanApiSource(
    private val apiKeys: List<String> = emptyList(),
    private val client: OkHttpClient = defaultClient()
) : SearchSource {

    override val id = "wanzhan"
    override val displayName = "万站API (聚合搜索)"
    override var enabled: Boolean = true

    private val keyRotationIndex = AtomicInteger(0)

    private data class KeyHealth(
        var consecutiveFailures: Int = 0,
        var cooldownUntilMs: Long = 0L
    )

    private val keyHealthMap = ConcurrentHashMap<String, KeyHealth>()

    companion object {
        private const val TAG = "WanzhanApiSource"
        private const val BASE_URL = "https://wzapi.com/api/jhsj"
        private const val USER_AGENT = "DaShenSou/1.0 (Android)"

        private const val MAX_KEY_FAILURES = 3
        private const val KEY_COOLDOWN_MS = 60_000L
        private const val MAX_RETRY_PER_KEY = 1
        private const val MAX_TOTAL_RETRIES = 2
        private const val BACKOFF_BASE_MS = 200L
        private const val BACKOFF_MAX_MS = 1_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun healthOf(key: String): KeyHealth =
        keyHealthMap.getOrPut(key) { KeyHealth() }

    private fun isKeyAvailable(key: String, now: Long): Boolean {
        val h = healthOf(key)
        return h.consecutiveFailures < MAX_KEY_FAILURES || now >= h.cooldownUntilMs
    }

    private fun markKeySuccess(key: String) {
        healthOf(key).let { h ->
            h.consecutiveFailures = 0
            h.cooldownUntilMs = 0L
        }
        Log.d(TAG, "key '${key.take(6)}...' success, reset health")
    }

    private fun markKeyFailure(key: String) {
        val h = healthOf(key)
        h.consecutiveFailures += 1
        if (h.consecutiveFailures >= MAX_KEY_FAILURES) {
            h.cooldownUntilMs = System.currentTimeMillis() + KEY_COOLDOWN_MS
            Log.w(TAG, "key '${key.take(6)}...' disabled for ${KEY_COOLDOWN_MS}ms (failures=${h.consecutiveFailures})")
        } else {
            Log.w(TAG, "key '${key.take(6)}...' failure ${h.consecutiveFailures}/$MAX_KEY_FAILURES")
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

        val keysToTry = if (apiKeys.isEmpty()) listOf<String?>(null) else {
            val now = System.currentTimeMillis()
            val ordered = apiKeys.sortedBy { if (isKeyAvailable(it, now)) 0 else 1 }
            if (ordered.none { isKeyAvailable(it, now) }) {
                ordered
            } else {
                ordered.filter { isKeyAvailable(it, now) }
            }
        }

        var lastError: String? = null
        var totalAttempts = 0

        for ((keyIdx, key) in keysToTry.withIndex()) {
            if (totalAttempts >= MAX_TOTAL_RETRIES) break

            for (retry in 0 until MAX_RETRY_PER_KEY) {
                if (totalAttempts >= MAX_TOTAL_RETRIES) break
                totalAttempts++

                if (keyIdx > 0 || retry > 0) {
                    val wait = backoffMs(keyIdx + retry)
                    Log.d(TAG, "backoff ${wait}ms before attempt total=$totalAttempts")
                    delay(wait)
                }

                val outcome = doRequest(keyword, page, category, key)
                when (outcome) {
                    is AttemptResult.Success -> {
                        if (key != null) markKeySuccess(key)
                        return@withContext SearchOutcome.Success(outcome.results)
                    }
                    is AttemptResult.AuthError -> {
                        if (key != null) markKeyFailure(key)
                        lastError = outcome.message
                        Log.w(TAG, "auth/quota error with key, try next: ${outcome.message}")
                        break
                    }
                    is AttemptResult.NetworkError -> {
                        if (key != null) markKeyFailure(key)
                        lastError = outcome.message
                        Log.w(TAG, "network error: ${outcome.message}, retry=${retry + 1}")
                    }
                    is AttemptResult.ParseError -> {
                        lastError = outcome.message
                        Log.w(TAG, "parse error: ${outcome.message}")
                        return@withContext SearchOutcome.Failure(outcome.message, outcome.cause)
                    }
                }
            }
        }

        SearchOutcome.Failure(lastError ?: "所有重试均失败")
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
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                Log.d(TAG, "response: code=${response.code} bodyLength=${response.body?.contentLength() ?: -1}")

                if (response.code == 429 || response.code == 401 || response.code == 403) {
                    return AttemptResult.AuthError("HTTP ${response.code} (限流/鉴权)")
                }
                if (!response.isSuccessful) {
                    return AttemptResult.AuthError("HTTP ${response.code}")
                }
                val body = response.body?.string()
                    ?: return AttemptResult.ParseError("响应体为空", null)

                val root = try {
                    JSONObject(body)
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
                        val note = item.optString("note", "")
                        val title = if (note.isBlank()) type else note
                        val source = item.optString("source", "")
                        val pwd = item.optString("password", "")
                        val date = item.optString("datetime", "")
                        val finalDate = if (date.isBlank() || date == "0001-01-01T00:00:00Z") "" else date
                        val fullTitle = if (source.isBlank()) title else "$title · $source"
                        val fileType = detectFileType(fullTitle)
                        if (!matchesCategory(category, fullTitle, fileType)) continue
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
                                category = category,
                                fileType = null,
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

    private fun matchesCategory(category: ResourceCategory, title: String, fileType: String?): Boolean {
        if (category == ResourceCategory.ALL) return true
        val effectiveType = fileType ?: detectFileType(title)
        val isEbook = effectiveType in listOf("pdf", "epub", "mobi", "txt", "ebook", "zip", "html", "azw3", "archive")
        val isVideo = effectiveType == "video"
        return when (category) {
            ResourceCategory.EBOOK -> isEbook
            ResourceCategory.MOVIE, ResourceCategory.TV -> isVideo
            else -> true
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

    private fun detectFileType(title: String): String? {
        val lower = title.lowercase()
        return when {
            lower.contains(".pdf") -> "pdf"
            lower.contains(".epub") -> "epub"
            lower.contains(".mobi") || lower.contains(".azw3") -> "mobi"
            lower.contains(".txt") -> "txt"
            lower.contains(".zip") || lower.contains(".rar") || lower.contains(".7z") -> "archive"
            lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".avi") || lower.contains(".rmvb") || lower.contains(".ts") || lower.contains(".mov") || lower.contains(".flv") -> "video"
            else -> null
        }
    }
}
