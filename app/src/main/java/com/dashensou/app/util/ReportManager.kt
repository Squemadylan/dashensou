package com.dashensou.app.util

import android.content.Context
import android.os.Build
import com.dashensou.app.BuildConfig
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 设备上线被动上报（双通道）：GitHub Issues + 钉钉机器人 webhook。
 *
 * - 24h 去重：SharedPreferences last_report_ts，同设备 24h 内只报一次。
 * - GitHub：先查 open issue（标题=deviceId），有则加 comment，无则建 issue。
 * - 钉钉：加签 text 消息；被封登录告警绕过 24h 去重，每次都推。
 * - 任一通道成功即更新 last_report_ts；全失败下次启动重试。
 */
class ReportManager(
    private val context: Context,
    private val banManager: BanManager
) {

    private val prefs = context.getSharedPreferences("cc_device", Context.MODE_PRIVATE)

    /**
     * 主入口：按需上报。**后台线程调用**。
     * @param reason "launch"=启动
     */
    fun reportIfNeeded(reason: String) {
        val now = System.currentTimeMillis()
        val last = prefs.getLong(KEY_LAST_REPORT, 0)
        if (last > 0 && now - last < TWENTY_FOUR_HOURS) {
            Log.d(TAG, "report skipped (within 24h), last=$last")
            return
        }

        val deviceId = banManager.getDeviceId()
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        val osVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val time = now.formatTime()

        val okGithub = try { reportToGithub(deviceId, model, osVer, time, reason) }
            catch (e: Exception) { Log.w(TAG, "github report failed: ${e.message}"); false }
        val okDing = try { reportToDingtalk(deviceId, model, osVer, time, reason) }
            catch (e: Exception) { Log.w(TAG, "dingtalk report failed: ${e.message}"); false }

        if (okGithub || okDing) {
            prefs.edit().putLong(KEY_LAST_REPORT, now).apply()
            Log.d(TAG, "report sent: github=$okGithub, ding=$okDing")
        } else {
            Log.w(TAG, "all report channels failed, will retry next launch")
        }
    }

    /**
     * 上报到 GitHub：单设备仅一个 open issue。
     * 先列 open issues 按标题匹配 deviceId；命中则加 comment，未命中才创建。
     */
    private fun reportToGithub(
        deviceId: String, model: String, osVer: String, time: String, reason: String
    ): Boolean {
        if (GITHUB_PAT.isBlank()) {
            Log.w(TAG, "GITHUB_PAT not set, skip github report")
            return false
        }
        val detail = "机型: $model\n系统: $osVer\n时间: $time\n原因: $reason"
        return try {
            val existing = findOpenIssueNumber(deviceId)
            if (existing != null) {
                val ok = githubPost(
                    "$GITHUB_API/$existing/comments",
                    JSONObject().put("body", "设备再次上线\n\n$detail").toString()
                )
                Log.d(TAG, "github comment on #$existing: $ok")
                ok
            } else {
                val body = JSONObject().apply {
                    put("title", deviceId)
                    put("body", "设备上线自动上报\n\n- 设备码: `$deviceId`\n- $detail")
                }.toString()
                val ok = githubPost(GITHUB_API, body, expectedCreated = true)
                Log.d(TAG, "github create issue: $ok")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "github report failed: ${e.message}")
            false
        }
    }

    /** 列出 open issues，返回标题等于 deviceId 的第一个 issue 编号；无则 null。 */
    private fun findOpenIssueNumber(deviceId: String): Long? {
        val conn = (URL("$GITHUB_API?state=open&per_page=100").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $GITHUB_PAT")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "list issues http $code")
                return null
            }
            val resp = conn.inputStream?.bufferedReader()?.use { it.readText() } ?: return null
            val arr = org.json.JSONArray(resp)
            for (i in 0 until arr.length()) {
                val it = arr.getJSONObject(i)
                if (it.has("pull_request")) continue
                if (it.optString("title").equals(deviceId, ignoreCase = true)) {
                    return it.getLong("number")
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** 统一的 GitHub POST（建 issue / 加 comment）。 */
    private fun githubPost(urlStr: String, body: String, expectedCreated: Boolean = false): Boolean {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "POST"
            doInput = true
            doOutput = true
            setRequestProperty("Authorization", "Bearer $GITHUB_PAT")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val ok = if (expectedCreated) code == 201 else code in 200..299
            if (!ok) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.w(TAG, "github POST $urlStr http $code: $err")
            }
            return ok
        } finally {
            conn.disconnect()
        }
    }

    private fun reportToDingtalk(
        deviceId: String, model: String, osVer: String, time: String, reason: String
    ): Boolean {
        val content = "[大神搜] 设备上线\n设备码: $deviceId\n机型: $model\n系统: $osVer\n时间: $time\n原因: $reason"
        return sendDingtalk(content)
    }

    /**
     * 被封禁设备尝试登录时的告警推送。**绕过 24h 去重**——每次封禁启动都通知。
     */
    fun reportBannedAttempt() {
        val now = System.currentTimeMillis()
        val deviceId = banManager.getDeviceId()
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"
        val osVer = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val time = now.formatTime()
        val content = "【大神搜】被封禁登录「已阻止」\n设备码: $deviceId\n机型: $model\n系统: $osVer\n时间: $time"
        Thread {
            try {
                val ok = sendDingtalk(content)
                Log.d(TAG, "banned attempt report sent: $ok")
            } catch (e: Exception) {
                Log.w(TAG, "banned attempt report failed: ${e.message}")
            }
        }.start()
    }

    /** 钉钉机器人发送 text 消息底层实现（加签）。errcode=0=成功。 */
    private fun sendDingtalk(content: String): Boolean {
        val conn = (URL(signedDingTalkUrl()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "POST"
            doInput = true
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            val body = JSONObject().apply {
                put("msgtype", "text")
                put("text", JSONObject().apply { put("content", content) })
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            val ok = code in 200..299 && try {
                JSONObject(resp).optString("errcode") == "0"
            } catch (e: Exception) { false }
            if (!ok) Log.w(TAG, "dingtalk http $code: $resp")
            return ok
        } finally {
            conn.disconnect()
        }
    }

    /** 钉钉加签：sign = URLEncode(Base64(HmacSHA256(timestamp + "\n" + secret, secret)))。 */
    private fun signedDingTalkUrl(): String {
        val timestamp = System.currentTimeMillis()
        val stringToSign = "$timestamp\n$DINGTALK_SECRET"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(DINGTALK_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sign = Base64.encodeToString(mac.doFinal(stringToSign.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val encodedSign = URLEncoder.encode(sign, "UTF-8")
        return "$DINGTALK_WEBHOOK&timestamp=$timestamp&sign=$encodedSign"
    }

    private fun Long.formatTime(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date(this))

    companion object {
        private const val TAG = "ReportManager"
        private const val KEY_LAST_REPORT = "last_report_ts"
        private const val TWENTY_FOUR_HOURS = 24L * 60 * 60 * 1000

        /** GitHub 细粒度 PAT（issues:write）。从 local.properties 注入，不提交仓库。 */
        private val GITHUB_PAT = BuildConfig.GITHUB_PAT

        /** GitHub REST API：issues 端点。 */
        private const val GITHUB_API = "https://api.github.com/repos/Squemadylan/dashensou/issues"

        /** 钉钉自定义机器人 webhook（加签模式）。从 local.properties 注入。 */
        private val DINGTALK_WEBHOOK = BuildConfig.DINGTALK_WEBHOOK

        /** 钉钉机器人加签密钥（SEC 开头）。从 local.properties 注入。 */
        private val DINGTALK_SECRET = BuildConfig.DINGTALK_SECRET
    }
}
