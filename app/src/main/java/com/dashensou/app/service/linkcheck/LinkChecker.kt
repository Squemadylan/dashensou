package com.dashensou.app.service.linkcheck

import android.util.Log
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.net.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side share-link probe with tiered TTL cache.
 *
 * Covers the high-traffic platforms (阿里 / 夸克 / 百度 / 123 / 迅雷).
 * Failures never throw — they return [LinkCheckStatus.UNCERTAIN].
 */
object LinkChecker {

    private const val TAG = "LinkChecker"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 10_000L
    private const val DEFAULT_CONCURRENCY = 3

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<ProbeResult>>()

    data class ProbeResult(
        val status: LinkCheckStatus,
        val reason: String = "",
        val cacheHit: Boolean = false
    )

    private data class CacheEntry(
        val status: LinkCheckStatus,
        val reason: String,
        val expiresAt: Long
    )

    private fun ttlMs(status: LinkCheckStatus): Long = when (status) {
        LinkCheckStatus.OK -> 24 * 60 * 60 * 1000L
        LinkCheckStatus.BAD -> 6 * 60 * 60 * 1000L
        LinkCheckStatus.LOCKED -> 12 * 60 * 60 * 1000L
        LinkCheckStatus.UNSUPPORTED -> 24 * 60 * 60 * 1000L
        LinkCheckStatus.UNCERTAIN -> 30 * 60 * 1000L
        else -> 0L
    }

    fun cacheKey(url: String, password: String?): String {
        val normalized = url.trim().substringBefore('#').lowercase(Locale.ROOT)
        val pwd = password?.trim().orEmpty()
        return if (pwd.isEmpty()) normalized else "$normalized|pwd=$pwd"
    }

    suspend fun check(
        url: String,
        password: String? = null,
        type: NetDiskType = NetDiskType.OTHER
    ): ProbeResult = withContext(Dispatchers.IO) {
        val key = cacheKey(url, password)
        val now = System.currentTimeMillis()
        cache[key]?.let { hit ->
            if (hit.expiresAt > now) {
                return@withContext ProbeResult(hit.status, hit.reason, cacheHit = true)
            }
            cache.remove(key)
        }

        if (url.startsWith("magnet:", ignoreCase = true) ||
            url.startsWith("ed2k:", ignoreCase = true) ||
            type == NetDiskType.DIRECT_URL
        ) {
            return@withContext remember(key, ProbeResult(LinkCheckStatus.UNSUPPORTED, "无法探活"))
        }

        val existing = inflight[key]
        if (existing != null) return@withContext existing.await()

        val created = CompletableDeferred<ProbeResult>()
        val winner = inflight.putIfAbsent(key, created)
        if (winner != null) return@withContext winner.await()

        try {
            val result = try {
                probe(url, password, type)
            } catch (t: Throwable) {
                Log.w(TAG, "probe failed: ${t.message}")
                ProbeResult(LinkCheckStatus.UNCERTAIN, t.message ?: "探活异常")
            }
            val remembered = remember(key, result)
            created.complete(remembered)
            remembered
        } catch (t: Throwable) {
            created.completeExceptionally(t)
            throw t
        } finally {
            inflight.remove(key, created)
        }
    }

    /**
     * Probe a batch of search hits. Emits each updated [SearchResult] via
     * [onUpdated] as soon as that row finishes (UI can refresh live).
     */
    suspend fun checkResults(
        results: List<SearchResult>,
        concurrency: Int = DEFAULT_CONCURRENCY,
        maxItems: Int = 24,
        onUpdated: suspend (SearchResult) -> Unit
    ) = coroutineScope {
        val targets = results
            .asSequence()
            .filter { it.url.startsWith("http") }
            .filter {
                it.netDiskType != NetDiskType.DIRECT_URL &&
                    it.netDiskType != NetDiskType.OTHER
            }
            .take(maxItems)
            .toList()
        if (targets.isEmpty()) return@coroutineScope

        val gate = Semaphore(concurrency.coerceIn(1, 8))
        targets.map { item ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    onUpdated(item.copy(linkCheckStatus = LinkCheckStatus.CHECKING))
                    val probe = check(item.url, item.extractionCode, item.netDiskType)
                    onUpdated(
                        item.copy(
                            linkCheckStatus = probe.status,
                            isValid = probe.status != LinkCheckStatus.BAD
                        )
                    )
                }
            }
        }.forEach { it.await() }
    }

    private fun remember(key: String, result: ProbeResult): ProbeResult {
        val ttl = ttlMs(result.status)
        if (ttl > 0 && result.status != LinkCheckStatus.CHECKING) {
            cache[key] = CacheEntry(
                status = result.status,
                reason = result.reason,
                expiresAt = System.currentTimeMillis() + ttl
            )
        }
        return result
    }

    private suspend fun probe(
        url: String,
        password: String?,
        type: NetDiskType
    ): ProbeResult {
        val host = runCatching { java.net.URI(url).host?.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        val resolved = when {
            type == NetDiskType.ALIYUN || host.contains("alipan") || host.contains("aliyundrive") ->
                checkAliyun(url)
            type == NetDiskType.QUARK || host.contains("pan.quark.cn") ->
                checkQuark(url, password.orEmpty())
            type == NetDiskType.BAIDU || host.contains("pan.baidu.com") ->
                checkBaidu(url, password.orEmpty())
            type == NetDiskType.YUNPAN123 || host.contains("123pan") || host.contains("123684") ||
                host.contains("123685") || host.contains("123912") ->
                check123(url)
            type == NetDiskType.XUNLEI || host.contains("pan.xunlei.com") ->
                checkXunlei(url, password.orEmpty())
            else -> ProbeResult(LinkCheckStatus.UNSUPPORTED, "平台暂不支持探活")
        }
        return resolved
    }

    // ---- 阿里 ----
    private suspend fun checkAliyun(url: String): ProbeResult {
        val id = Regex("""/(?:s|t)/([a-zA-Z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "无法解析分享 ID")
        val body = JSONObject().put("share_id", id).toString()
        val request = Request.Builder()
            .url("https://api.aliyundrive.com/adrive/v3/share_link/get_share_by_anonymous?share_id=$id")
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .header("Origin", "https://www.alipan.com")
            .header("Referer", "https://www.alipan.com/")
            .header("x-canary", "client=web,app=share,version=v2.3.1")
            .post(body.toRequestBody(jsonMedia))
            .build()
        val resp = HttpClient.execute(request, TIMEOUT_MS)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "网络失败")
        return resp.use { r ->
            val text = r.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
                ?: return@use ProbeResult(LinkCheckStatus.UNCERTAIN, "HTTP ${r.code}")
            classifyAliyun(json, r.code)
        }
    }

    private fun classifyAliyun(rsp: JSONObject, statusCode: Int): ProbeResult {
        val code = rsp.optString("code").lowercase(Locale.ROOT)
        if (code.isNotBlank()) {
            val message = rsp.optString("message").ifBlank { code }
            if (code.contains("sharelink") ||
                listOf("notfound", "cancelled", "canceled", "forbidden", "expired")
                    .any { code.contains(it) }
            ) {
                return ProbeResult(LinkCheckStatus.BAD, message)
            }
            return ProbeResult(LinkCheckStatus.UNCERTAIN, message)
        }
        if (rsp.optInt("file_count", -1) == 0 && rsp.optString("share_name").isBlank()) {
            return ProbeResult(LinkCheckStatus.BAD, "分享内容为空")
        }
        val shareStatus = rsp.optString("share_status").lowercase(Locale.ROOT)
        if (shareStatus.isNotBlank() &&
            shareStatus != "enabled" &&
            shareStatus != "normal" &&
            listOf("forbidden", "cancel", "expired", "illegal", "invalid", "disabled")
                .any { shareStatus.contains(it) }
        ) {
            return ProbeResult(LinkCheckStatus.BAD, "链接失效")
        }
        if (statusCode == 200 &&
            (rsp.optString("share_name").isNotBlank() ||
                rsp.optString("share_title").isNotBlank() ||
                rsp.optInt("file_count", 0) > 0)
        ) {
            return ProbeResult(LinkCheckStatus.OK, "链接有效")
        }
        return ProbeResult(LinkCheckStatus.UNCERTAIN, "无法确认")
    }

    // ---- 夸克 ----
    private suspend fun checkQuark(url: String, password: String): ProbeResult {
        val id = Regex("""pan\.quark\.cn/s/([a-zA-Z0-9]+)""")
            .find(url)?.groupValues?.getOrNull(1)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "无法解析分享 ID")
        val pwd = password.ifBlank {
            Regex("""[?&]pwd=([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1).orEmpty()
        }
        val tokenBody = JSONObject()
            .put("pwd_id", id)
            .put("passcode", pwd)
            .put("support_visit_limit_private_share", true)
            .toString()
        val tokenReq = Request.Builder()
            .url("https://drive-h.quark.cn/1/clouddrive/share/sharepage/token")
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .header("Origin", "https://pan.quark.cn")
            .header("Referer", "https://pan.quark.cn/")
            .post(tokenBody.toRequestBody(jsonMedia))
            .build()
        val tokenResp = HttpClient.execute(tokenReq, TIMEOUT_MS)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "网络失败")
        val (tokenOutcome, stoken) = tokenResp.use { r ->
            val json = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                ?: return@use (ProbeResult(LinkCheckStatus.UNCERTAIN, "HTTP ${r.code}") to "")
            classifyQuarkToken(json)
        }
        if (tokenOutcome.status != LinkCheckStatus.OK) return tokenOutcome
        if (stoken.isBlank()) {
            return ProbeResult(LinkCheckStatus.UNCERTAIN, "访问令牌缺失")
        }

        val detailUrl =
            "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/detail" +
                "?pwd_id=${URLEncoder.encode(id, "UTF-8")}" +
                "&stoken=${URLEncoder.encode(stoken, "UTF-8")}&ver=2&pr=ucpro"
        val detailReq = Request.Builder()
            .url(detailUrl)
            .header("User-Agent", UA)
            .header("Origin", "https://pan.quark.cn")
            .header("Referer", "https://pan.quark.cn/")
            .get()
            .build()
        val detailResp = HttpClient.execute(detailReq, TIMEOUT_MS)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "网络失败")
        return detailResp.use { r ->
            val json = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                ?: return@use ProbeResult(LinkCheckStatus.UNCERTAIN, "HTTP ${r.code}")
            classifyQuarkDetail(json)
        }
    }

    private fun classifyQuarkToken(rsp: JSONObject): Pair<ProbeResult, String> {
        val code = rsp.optInt("code", -1)
        val msg = rsp.optString("message")
        when (code) {
            0 -> Unit
            41008 -> return ProbeResult(LinkCheckStatus.LOCKED, "需要提取码") to ""
            41004, 41010, 41011 -> return ProbeResult(LinkCheckStatus.BAD, "链接失效") to ""
            else -> {
                if (containsAny(msg, listOf("不存在", "失效", "违规", "过期", "取消"))) {
                    return ProbeResult(LinkCheckStatus.BAD, msg) to ""
                }
                if (containsAny(msg, listOf("提取码", "密码"))) {
                    return ProbeResult(LinkCheckStatus.LOCKED, msg) to ""
                }
                if (code != 0) return ProbeResult(LinkCheckStatus.UNCERTAIN, msg.ifBlank { "code=$code" }) to ""
            }
        }
        val stoken = rsp.optJSONObject("data")?.optString("stoken").orEmpty()
        if (stoken.isBlank()) {
            return ProbeResult(LinkCheckStatus.UNCERTAIN, "访问令牌缺失") to ""
        }
        return ProbeResult(LinkCheckStatus.OK, "token ok") to stoken
    }

    private fun classifyQuarkDetail(rsp: JSONObject): ProbeResult {
        val code = rsp.optInt("code", -1)
        if (code != 0) {
            val message = rsp.optString("message").ifBlank { "无法确认" }
            if (containsAny(message, listOf("提取码", "密码", "passcode"))) {
                return ProbeResult(LinkCheckStatus.LOCKED, message)
            }
            if (containsAny(message, listOf("不存在", "失效", "违规", "过期", "取消"))) {
                return ProbeResult(LinkCheckStatus.BAD, message)
            }
            return ProbeResult(LinkCheckStatus.UNCERTAIN, message)
        }
        val data = rsp.optJSONObject("data")
        val list = data?.optJSONArray("list")
        val share = data?.optJSONObject("share")
        val listLen = list?.length() ?: 0
        if (listLen == 0) {
            val status = share?.optInt("status", 0) ?: 0
            if (status > 1) return ProbeResult(LinkCheckStatus.BAD, "分享已失效")
            if (data?.optBoolean("is_expire", false) == true) {
                return ProbeResult(LinkCheckStatus.BAD, "分享已过期")
            }
            return ProbeResult(LinkCheckStatus.BAD, "文件列表为空")
        }
        return ProbeResult(LinkCheckStatus.OK, "链接有效")
    }

    // ---- 百度 ----
    private suspend fun checkBaidu(url: String, password: String): ProbeResult {
        val short = Regex("""pan\.baidu\.com/s/1([a-zA-Z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""pan\.baidu\.com/s/([a-zA-Z0-9_-]+)""")
                .find(url)?.groupValues?.getOrNull(1)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "无法解析分享 ID")
        val shortUrl = if (short.startsWith("1")) short else "1$short"
        val pwd = password.ifBlank {
            Regex("""[?&]pwd=([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1).orEmpty()
        }
        val listUrl =
            "https://pan.baidu.com/share/list?web=1&page=1&num=20&order=time&desc=1" +
                "&showempty=0&shorturl=${URLEncoder.encode(shortUrl, "UTF-8")}&root=1&clienttype=0"
        val listReq = Request.Builder()
            .url(listUrl)
            .header("User-Agent", UA)
            .header("Referer", url)
            .get()
            .build()
        val listResp = HttpClient.execute(listReq, TIMEOUT_MS)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "网络失败")
        return listResp.use { r ->
            val json = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                ?: return@use ProbeResult(LinkCheckStatus.UNCERTAIN, "HTTP ${r.code}")
            val errno = json.optInt("errno", -999)
            when (errno) {
                0 -> ProbeResult(LinkCheckStatus.OK, "链接有效")
                -9, -12 -> {
                    if (pwd.isBlank()) ProbeResult(LinkCheckStatus.LOCKED, "需要提取码")
                    else ProbeResult(LinkCheckStatus.LOCKED, "提取码错误或需验证")
                }
                -62, 105, 2 -> ProbeResult(LinkCheckStatus.BAD, "链接失效")
                else -> ProbeResult(LinkCheckStatus.UNCERTAIN, "errno=$errno")
            }
        }
    }

    // ---- 123 ----
    private suspend fun check123(url: String): ProbeResult {
        val key = Regex("""/s/([a-zA-Z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "无法解析分享码")
        val req = Request.Builder()
            .url("https://www.123pan.com/api/share/info?shareKey=${URLEncoder.encode(key, "UTF-8")}")
            .header("User-Agent", UA)
            .get()
            .build()
        val resp = HttpClient.execute(req, TIMEOUT_MS)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "网络失败")
        return resp.use { r ->
            if (r.code == 404 || r.code == 410) {
                return@use ProbeResult(LinkCheckStatus.BAD, "链接失效")
            }
            val json = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
                ?: return@use ProbeResult(LinkCheckStatus.UNCERTAIN, "HTTP ${r.code}")
            val code = json.optInt("code", -1)
            when {
                code == 0 -> ProbeResult(LinkCheckStatus.OK, "链接有效")
                containsAny(json.optString("message"), listOf("失效", "不存在", "过期", "取消")) ->
                    ProbeResult(LinkCheckStatus.BAD, json.optString("message"))
                else -> ProbeResult(LinkCheckStatus.UNCERTAIN, json.optString("message").ifBlank { "code=$code" })
            }
        }
    }

    // ---- 迅雷 ----
    private suspend fun checkXunlei(url: String, password: String): ProbeResult {
        val id = Regex("""pan\.xunlei\.com/s/([a-zA-Z0-9_-]+)""")
            .find(url)?.groupValues?.getOrNull(1)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "无法解析分享 ID")
        val pwd = password.ifBlank {
            Regex("""[?&]pwd=([a-zA-Z0-9]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.getOrNull(1).orEmpty()
        }
        val api =
            "https://api-pan.xunlei.com/drive/v1/share?share_id=${URLEncoder.encode(id, "UTF-8")}" +
                "&pass_code=${URLEncoder.encode(pwd, "UTF-8")}&limit=20"
        val req = Request.Builder()
            .url(api)
            .header("User-Agent", UA)
            .header("Origin", "https://pan.xunlei.com")
            .header("Referer", "https://pan.xunlei.com/")
            .get()
            .build()
        val resp = HttpClient.execute(req, TIMEOUT_MS)
            ?: return ProbeResult(LinkCheckStatus.UNCERTAIN, "网络失败")
        return resp.use { r ->
            when (r.code) {
                404, 403 -> ProbeResult(LinkCheckStatus.BAD, "链接失效")
                else -> {
                    val text = r.body?.string().orEmpty()
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    when {
                        json == null && r.code in 200..299 ->
                            ProbeResult(LinkCheckStatus.UNCERTAIN, "响应非 JSON")
                        json?.has("share_status") == true &&
                            json.optString("share_status").equals("OK", true) ->
                            ProbeResult(LinkCheckStatus.OK, "链接有效")
                        containsAny(text, listOf("PASS_CODE", "pass_code", "需要提取")) ->
                            ProbeResult(LinkCheckStatus.LOCKED, "需要提取码")
                        containsAny(text, listOf("NOT_FOUND", "EXPIRED", "INVALID", "失效")) ->
                            ProbeResult(LinkCheckStatus.BAD, "链接失效")
                        r.code in 200..299 -> ProbeResult(LinkCheckStatus.OK, "链接有效")
                        else -> ProbeResult(LinkCheckStatus.UNCERTAIN, "HTTP ${r.code}")
                    }
                }
            }
        }
    }

    private fun containsAny(text: String, keys: List<String>): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase(Locale.ROOT)
        return keys.any { lower.contains(it.lowercase(Locale.ROOT)) }
    }

    fun clearCache() {
        cache.clear()
    }
}
