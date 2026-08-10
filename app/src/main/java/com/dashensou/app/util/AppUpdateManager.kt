package com.dashensou.app.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.dashensou.app.App
import com.dashensou.app.BuildConfig
import com.dashensou.app.R
import com.dashensou.app.data.model.UpdateManifest
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val SCRIM_TAG = "app_update_scrim"
    private const val PREFS_KEY_LAST_OPTIONAL_CHECK_MS = "last_optional_update_check_ms"
    private const val OPTIONAL_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    private val gson = Gson()
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 拉取 update.json 专用，失败时尽快走内置兜底 */
    private val manifestHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val isDownloading = AtomicBoolean(false)
    private val isUpdateDialogVisible = AtomicBoolean(false)

    @Volatile
    private var activeUpdateUi: ActiveUpdateUi? = null

    @Volatile
    private var scrimHostActivity: FragmentActivity? = null

    private data class ActiveUpdateUi(
        val dialog: AlertDialog,
        val messageView: TextView,
        val progressBar: ProgressBar,
        val progressText: TextView,
        val force: Boolean
    )

    sealed class UpdateStatus {
        data object UpToDate : UpdateStatus()
        data class Optional(val manifest: UpdateManifest) : UpdateStatus()
        data class ForceRequired(val manifest: UpdateManifest) : UpdateStatus()
    }

    /** 内置网盘链接；若 [manifest] 含 manualUpdateUrl 则优先使用远程配置 */
    fun resolveManualUpdateUrl(context: Context, manifest: UpdateManifest? = null): String {
        val remote = manifest?.manualUpdateUrl?.trim().orEmpty()
        if (remote.startsWith("https://")) return remote
        return context.getString(R.string.update_manual_url).trim()
    }

    fun openManualUpdatePage(
        context: Context,
        manifest: UpdateManifest? = null,
        urlOverride: String? = null
    ): Boolean {
        val url = urlOverride?.trim()?.takeIf { it.startsWith("https://") }
            ?: resolveManualUpdateUrl(context, manifest)
        if (url.isBlank()) {
            toast(context, context.getString(R.string.update_manual_open_failed, ""))
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open manual update url", e)
            copyManualUpdateUrlToClipboard(context, url)
            toast(context, context.getString(R.string.update_manual_open_failed, url))
            false
        }
    }

    private fun copyManualUpdateUrlToClipboard(context: Context, url: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("manual_update_url", url))
        }
    }

    fun localVersionCode(context: Context): Int {
        val pm = context.packageManager
        val pkg = context.packageName
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, 0).longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, 0).versionCode
            }
        } catch (_: PackageManager.NameNotFoundException) {
            0
        }
    }

    fun evaluate(localVersionCode: Int, manifest: UpdateManifest): UpdateStatus {
        if (manifest.versionCode <= 0 || manifest.minVersionCode <= 0) {
            throw IllegalArgumentException("update.json 中 versionCode / minVersionCode 无效")
        }
        if (localVersionCode < manifest.minVersionCode) {
            return UpdateStatus.ForceRequired(manifest)
        }
        if (localVersionCode < manifest.versionCode) {
            return UpdateStatus.Optional(manifest)
        }
        return UpdateStatus.UpToDate
    }

    private fun manifestUrls(): List<String> = listOfNotNull(
        BuildConfig.UPDATE_MANIFEST_URL.trim().takeIf { it.isNotEmpty() },
        BuildConfig.UPDATE_MANIFEST_URL_MIRROR.trim().takeIf { it.isNotEmpty() },
        // 旧安装包 BuildConfig 可能仍指向历史 commit；始终再试 main，取 versionCode 最高的一份
        "https://raw.githubusercontent.com/Squemadylan/dashensou/main/app/update.json",
        "https://cdn.jsdelivr.net/gh/Squemadylan/dashensou@main/app/update.json"
    ).distinct()

    /**
     * @param allowEmbeddedFallback 手动检查应为 false，避免内置旧配置掩盖线上新版本
     */
    suspend fun fetchManifest(
        context: Context,
        allowEmbeddedFallback: Boolean = true
    ): Result<UpdateManifest> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        val loaded = mutableListOf<Pair<String, UpdateManifest>>()
        for (url in manifestUrls()) {
            val result = fetchManifestFromUrl(url)
            if (result.isSuccess) {
                val manifest = result.getOrThrow()
                loaded.add(url to manifest)
                Log.d(TAG, "Loaded update manifest from $url (versionCode=${manifest.versionCode})")
            } else {
                lastError = result.exceptionOrNull()
                Log.w(TAG, "Failed to load manifest from $url", lastError)
            }
        }
        if (loaded.isNotEmpty()) {
            val best = loaded.maxBy { it.second.versionCode }
            if (loaded.size > 1) {
                Log.i(TAG, "Using newest manifest from ${best.first} among ${loaded.size} sources")
            }
            return@withContext Result.success(best.second)
        }
        if (allowEmbeddedFallback) {
            loadEmbeddedFallbackManifest(context)?.let { manifest ->
                Log.i(TAG, "Using embedded fallback update manifest (versionCode=${manifest.versionCode})")
                return@withContext Result.success(manifest)
            }
        }
        Result.failure(lastError ?: IllegalStateException("无法获取更新配置"))
    }

    private fun fetchManifestFromUrl(url: String): Result<UpdateManifest> = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        manifestHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code}")
            }
            parseManifestJson(response.body?.string().orEmpty())
        }
    }

    private fun loadEmbeddedFallbackManifest(context: Context): UpdateManifest? = runCatching {
        context.assets.open("update_manifest_fallback.json").bufferedReader().use { reader ->
            parseManifestJson(reader.readText())
        }
    }.onFailure { e ->
        Log.w(TAG, "Embedded fallback manifest unavailable", e)
    }.getOrNull()

    private fun parseManifestJson(body: String): UpdateManifest {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) error("更新清单为空")
        val manifest = gson.fromJson(trimmed, UpdateManifest::class.java)
        validateManifest(manifest)
        return manifest
    }

    private fun validateManifest(manifest: UpdateManifest) {
        if (manifest.versionCode <= 0 || manifest.minVersionCode <= 0) {
            error("versionCode / minVersionCode 必须大于 0")
        }
        if (manifest.minVersionCode > manifest.versionCode) {
            error("minVersionCode 不能大于 versionCode")
        }
        if (manifest.apkUrl.isBlank()) {
            error("apkUrl 不能为空")
        }
        if (!manifest.apkUrl.startsWith("https://")) {
            error("apkUrl 必须使用 HTTPS")
        }
    }

    /** 启动时检查：强制更新立即提示；可选更新 24 小时内只查一次 */
    fun runStartupCheck(activity: AppCompatActivity) {
        if (isUpdateDialogVisible.get()) return
        activity.lifecycleScope.launch {
            val localCode = localVersionCode(activity)
            val manifestResult = fetchManifest(activity)
            manifestResult.onFailure { e ->
                Log.w(TAG, "Startup update check failed", e)
                return@launch
            }
            val manifest = manifestResult.getOrThrow()
            when (val status = evaluate(localCode, manifest)) {
                is UpdateStatus.ForceRequired -> {
                    Log.i(
                        TAG,
                        "Force update required: local=$localCode min=${status.manifest.minVersionCode}"
                    )
                    showUpdateDialog(activity, status.manifest, force = true)
                }
                is UpdateStatus.Optional -> {
                    if (shouldRunOptionalCheck(activity)) {
                        markOptionalCheckDone(activity)
                        showUpdateDialog(activity, status.manifest, force = false)
                    }
                }
                is UpdateStatus.UpToDate -> Unit
            }
        }
    }

    /** 「我的」页手动检查：始终请求网络并给出明确结果 */
    fun runManualCheck(activity: FragmentActivity) {
        if (isUpdateDialogVisible.get()) {
            toast(activity, activity.getString(R.string.update_check_in_progress))
            return
        }
        activity.lifecycleScope.launch {
            toast(activity, activity.getString(R.string.update_checking))
            val localCode = localVersionCode(activity)
            val manifestResult = fetchManifest(activity, allowEmbeddedFallback = false)
            manifestResult.onFailure { e ->
                Log.w(TAG, "Manual update check failed", e)
                toast(
                    activity,
                    activity.getString(R.string.update_check_failed, e.message ?: "未知错误")
                )
                return@launch
            }
            val manifest = manifestResult.getOrThrow()
            when (evaluate(localCode, manifest)) {
                is UpdateStatus.UpToDate -> {
                    toast(activity, activity.getString(R.string.update_already_latest))
                }
                is UpdateStatus.Optional -> showUpdateDialog(activity, manifest, force = false)
                is UpdateStatus.ForceRequired -> showUpdateDialog(activity, manifest, force = true)
            }
        }
    }

    private fun shouldRunOptionalCheck(context: Context): Boolean {
        val last = context.applicationContext
            .getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(PREFS_KEY_LAST_OPTIONAL_CHECK_MS, 0L)
        return System.currentTimeMillis() - last >= OPTIONAL_CHECK_INTERVAL_MS
    }

    private fun markOptionalCheckDone(context: Context) {
        context.applicationContext
            .getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(PREFS_KEY_LAST_OPTIONAL_CHECK_MS, System.currentTimeMillis())
            .apply()
    }

    private fun buildUpdateMessage(activity: FragmentActivity, force: Boolean): String =
        if (force) {
            activity.getString(R.string.update_message_force)
        } else {
            activity.getString(R.string.update_message_optional)
        }

    private fun showUpdateDialog(
        activity: FragmentActivity,
        manifest: UpdateManifest,
        force: Boolean
    ) {
        if (!isUpdateDialogVisible.compareAndSet(false, true)) return

        val title = if (force) {
            activity.getString(R.string.update_force_title)
        } else {
            activity.getString(R.string.update_optional_title)
        }

        activity.window.decorView.post {
            if (activity.isFinishing || activity.isDestroyed) {
                isUpdateDialogVisible.set(false)
                return@post
            }
            val content = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null)
            val messageView = content.findViewById<TextView>(R.id.tvUpdateMessage)
            val progressBar = content.findViewById<ProgressBar>(R.id.progressUpdate)
            val progressText = content.findViewById<TextView>(R.id.tvUpdateProgress)
            messageView.text = buildUpdateMessage(activity, force)

            val builder = MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setView(content)
                .setCancelable(!force)
                // null：避免点击后立即 dismiss，下载时保持弹窗
                .setPositiveButton(activity.getString(R.string.update_action_download), null)
            if (force) {
                builder.setNegativeButton(activity.getString(R.string.update_action_manual_pan), null)
            } else {
                builder.setNegativeButton(activity.getString(R.string.update_action_later), null)
            }
            val dialog = builder.create()
            applyUpdateDialogWindowEffects(dialog)
            dialog.setCanceledOnTouchOutside(false)
            if (force) {
                dialog.setOnKeyListener { _, keyCode, event ->
                    keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
                }
            }
            attachUpdateScrim(activity)
            dialog.setOnDismissListener {
                detachUpdateScrim()
                activeUpdateUi = null
                isUpdateDialogVisible.set(false)
            }
            activeUpdateUi = ActiveUpdateUi(dialog, messageView, progressBar, progressText, force)
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                    beginDownloadInDialog(activity, manifest)
                }
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                    if (force) {
                        openManualUpdatePage(activity, manifest)
                        messageView.text = activity.getString(R.string.update_manual_opened_hint)
                    } else {
                        dialog.dismiss()
                    }
                }
            }
            dialog.show()
        }
    }

    /** 底层虚化蒙版 + 拦截点击，弹窗叠在上方 */
    private fun attachUpdateScrim(activity: FragmentActivity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.findViewWithTag<View>(SCRIM_TAG) != null) return
        val scrim = FrameLayout(activity).apply {
            tag = SCRIM_TAG
            setBackgroundResource(R.drawable.bg_update_scrim)
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.addView(
            scrim,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        scrimHostActivity = activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.window.decorView.setRenderEffect(
                RenderEffect.createBlurEffect(22f, 22f, Shader.TileMode.CLAMP)
            )
        }
    }

    private fun detachUpdateScrim() {
        val activity = scrimHostActivity ?: return
        scrimHostActivity = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.window.decorView.setRenderEffect(null)
        }
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        root.findViewWithTag<View>(SCRIM_TAG)?.let { root.removeView(it) }
    }

    private fun applyUpdateDialogWindowEffects(dialog: AlertDialog) {
        dialog.window?.let { window ->
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply {
                dimAmount = 0.45f
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                window.setBackgroundBlurRadius(48)
            }
        }
    }

    private fun beginDownloadInDialog(activity: FragmentActivity, manifest: UpdateManifest) {
        val ui = activeUpdateUi ?: return
        if (!isDownloading.compareAndSet(false, true)) {
            toast(activity, activity.getString(R.string.update_download_in_progress))
            return
        }
        ui.dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        // 强制更新时保留「网盘手动更新」，便于下载失败时切换渠道
        if (!ui.force) {
            ui.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        }
        ui.messageView.text = activity.getString(R.string.update_download_preparing)
        ui.progressBar.visibility = View.VISIBLE
        ui.progressBar.isIndeterminate = true
        ui.progressText.visibility = View.VISIBLE
        ui.progressText.text = activity.getString(R.string.update_download_preparing)

        activity.lifecycleScope.launch {
            try {
                if (!ensureInstallPermission(activity)) {
                    restoreDialogAfterDownloadFailure(
                        activity.getString(R.string.update_install_permission_required)
                    )
                    return@launch
                }
                val apkFile = withContext(Dispatchers.IO) {
                    downloadApk(activity.applicationContext, manifest) { downloaded, total ->
                        activity.runOnUiThread {
                            updateDownloadProgressUi(activity, downloaded, total)
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    showDownloadCompleteUi(activity)
                    launchPackageInstaller(activity, apkFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download or install failed", e)
                restoreDialogAfterDownloadFailure(
                    activity.getString(R.string.update_download_failed_in_dialog)
                )
            } finally {
                isDownloading.set(false)
            }
        }
    }

    private fun updateDownloadProgressUi(activity: FragmentActivity, downloaded: Long, total: Long) {
        val ui = activeUpdateUi ?: return
        ui.progressBar.visibility = View.VISIBLE
        ui.progressText.visibility = View.VISIBLE
        if (total > 0L) {
            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
            ui.progressBar.isIndeterminate = false
            ui.progressBar.progress = percent
            ui.progressText.text = activity.getString(R.string.update_downloading_percent, percent)
        } else {
            ui.progressBar.isIndeterminate = true
            ui.progressText.text = activity.getString(R.string.update_downloading_unknown)
        }
    }

    private fun showDownloadCompleteUi(activity: FragmentActivity) {
        val ui = activeUpdateUi ?: return
        ui.progressBar.isIndeterminate = false
        ui.progressBar.progress = 100
        ui.progressText.text = activity.getString(R.string.update_downloading_percent, 100)
        ui.messageView.text = activity.getString(R.string.update_download_complete)
    }

    private fun restoreDialogAfterDownloadFailure(errorMessage: String) {
        val ui = activeUpdateUi ?: return
        ui.progressBar.visibility = View.GONE
        ui.progressText.visibility = View.GONE
        ui.messageView.text = errorMessage
        ui.dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
        ui.dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
    }

    private suspend fun downloadApk(
        context: Context,
        manifest: UpdateManifest,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val outFile = File(dir, "dashensou_update.apk")
        if (outFile.exists()) outFile.delete()

        val request = Request.Builder().url(manifest.apkUrl).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载失败 HTTP ${response.code}")
            val body = response.body ?: error("下载内容为空")
            val total = body.contentLength().coerceAtLeast(0L)
            onProgress(0L, total)
            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buffer = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                    output.flush()
                }
            }
        }

        val expectedHash = manifest.sha256?.trim()?.lowercase().orEmpty()
        if (expectedHash.isNotEmpty()) {
            val actual = sha256Hex(outFile)
            if (actual != expectedHash) {
                outFile.delete()
                error("安装包校验失败，请检查 update.json 中的 sha256")
            }
        }
        return outFile
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ensureInstallPermission(activity: FragmentActivity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        if (activity.packageManager.canRequestPackageInstalls()) return true
        toast(activity, activity.getString(R.string.update_install_permission_required))
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}")
        )
        activity.startActivity(intent)
        return false
    }

    private fun launchPackageInstaller(activity: FragmentActivity, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun toast(context: Context, message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }
}
