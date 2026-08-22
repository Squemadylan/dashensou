package com.dashensou.app.util

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.dashensou.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 设备识别码 + GitHub 仓库封禁列表管理（零服务器）。
 *
 * - 设备码：ANDROID_ID（16位hex），应用签名+用户稳定，无需权限。
 * - 封禁列表：通过 GitHub Contents API 拉 banlist.txt（一行一个设备码，#开头注释），
 *   实时无延迟（commit 后立即生效，不走 raw CDN）。
 * - 缓存：filesDir/banned_cache.txt，第一行时间戳(ms)，后续为列表行；离线兜底。
 */
class BanManager(private val context: Context) {

    /** GitHub Contents API 端点（实时，无 raw CDN 延迟） */
    private val banApiUrl =
        "https://api.github.com/repos/Squemadylan/dashensou/contents/banlist.txt"

    /** 本地缓存：第一行=上次成功拉取时间戳(ms)，后续=封禁列表行 */
    private val cacheFile: File = File(context.filesDir, "banned_cache.txt")

    /** 缓存设备码，避免每次取 ANDROID_ID */
    private val prefs = context.getSharedPreferences("cc_device", Context.MODE_PRIVATE)

    /**
     * 获取本机设备识别码（ANDROID_ID，16位hex小写）。
     * 首次取到后缓存到 SharedPreferences；极端取不到用时间戳兜底，保证有唯一码可上报。
     */
    fun getDeviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "getDeviceId error: ${e.message}")
            ""
        }
        val final = if (id.isBlank()) "unknown-" + System.currentTimeMillis().toString(16)
                    else id.lowercase()
        prefs.edit().putString(KEY_DEVICE_ID, final).apply()
        return final
    }

    /**
     * 判断本机是否被封禁。**后台线程调用**。
     * 拉 banlist.txt → 命中本机 deviceId 返回 true；拉取失败用本地缓存；缓存也无返回 false（放行，不误伤）。
     */
    fun isBanned(): Boolean {
        val myId = getDeviceId()
        val list = try {
            fetchBannedList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchBannedList failed, fallback to cache: ${e.message}")
            loadCache().second
        }
        val hit = list.any { it.equals(myId, ignoreCase = true) }
        Log.d(TAG, "isBanned: myId=$myId, listSize=${list.size}, hit=$hit")
        return hit
    }

    /**
     * 通过 GitHub Contents API 拉取封禁列表，**实时无延迟**（不走 raw CDN）。
     * 公开仓库不需 PAT，但带 PAT 可获 5000 req/h 额度（无 PAT 仅 60 req/h）。
     * API 返回 JSON，content 是 Base64 编码的文件内容。
     * 60s 超时（网络不通时最多等 60s）；超时抛异常 → isBanned() catch 走缓存兜底 → 无缓存放行。
     */
    private fun fetchBannedList(): List<String> {
        val conn = (URL(banApiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 60000
            readTimeout = 60000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            // 带 PAT 提升速率额度，不带也能用（公开仓库）
            if (GITHUB_PAT.isNotEmpty()) {
                setRequestProperty("Authorization", "Bearer $GITHUB_PAT")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            val body = conn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            val content = JSONObject(body)
                .optString("content", "")
            // API 返回的 content 是 Base64 编码，decode 后才是实际文本
            val decoded = if (content.isNotEmpty()) {
                String(Base64.decode(content.replace("\n", ""), Base64.DEFAULT))
            } else ""
            val list = parseList(decoded)
            saveCache(list)
            return list
        } finally {
            conn.disconnect()
        }
    }

    /** 解析 banlist.txt：一行一个，# 注释，空行跳过，trim 小写 */
    private fun parseList(body: String): List<String> =
        body.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.lowercase() }

    private fun saveCache(list: List<String>) {
        try {
            cacheFile.writeText("${System.currentTimeMillis()}\n${list.joinToString("\n")}")
        } catch (e: Exception) {
            Log.w(TAG, "saveCache failed: ${e.message}")
        }
    }

    private fun loadCache(): Pair<Long, List<String>> {
        if (!cacheFile.exists()) return 0L to emptyList()
        return try {
            val lines = cacheFile.readLines()
            if (lines.isEmpty()) return 0L to emptyList()
            val ts = lines[0].toLongOrNull() ?: 0L
            val list = lines.drop(1).filter { it.isNotEmpty() }.map { it.lowercase() }
            ts to list
        } catch (e: Exception) {
            0L to emptyList()
        }
    }

    companion object {
        private const val TAG = "BanManager"
        private const val KEY_DEVICE_ID = "device_id"

        /** GitHub PAT（从 BuildConfig 注入，不硬编码）。 */
        private val GITHUB_PAT = BuildConfig.GITHUB_PAT
    }
}