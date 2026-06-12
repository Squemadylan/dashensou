package com.dashensou.app.service

import android.app.DownloadManager as AndroidDownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.dashensou.app.App
import com.dashensou.app.data.model.DownloadRecord
import com.dashensou.app.data.model.DownloadStatus
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.util.NetDiskUtils
import com.dashensou.app.util.MediaStorePaths
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns two write paths into the device download subsystem:
 *
 *  1. [enqueueDirectDownload] is the path every net-disk-or-direct result
 *     goes through. It writes bytes through OkHttp + MediaStore.Downloads
 *     (Android 10+ scoped storage can't create Download/<subdir>/ through
 *     the system DownloadManager without MANAGE_EXTERNAL_STORAGE). The
 *     recorded `filePath` is the public "Download/Book/xxx.txt" string so
 *     [com.dashensou.app.util.FileOpener] can re-locate the file later.
 *
 *  2. [openNetDiskApp] opens the installed net-disk app (夸克 / 度盘 / ...)
 *     via scheme + chooser fallbacks. The system DownloadManager is not
 *     involved — we never copy the bytes; the user's net-disk app does.
 *
 * P1#13: the old `downloadFile` / `downloadFileFallback` pair used an
 * app-private fallback path "DaShenSou/<sub>/xxx.txt" with a lowercased
 * subdirectory, and the *current* `enqueueDirectDownload` uses the public
 * MediaStore path with an upper-cased subdir. The two paths had drifted
 * so far that `FileOpener` needed a basename-only fallback to bridge
 * between them. Now that every write goes through `enqueueDirectDownload`
 * the legacy pair is dead code and the basename-only fallback is only
 * there to rescue records written by older installs.
 *
 * P0#robustness: this is a process-wide singleton. The previous design
 * constructed a fresh instance in MainActivity.onCreate, which registered
 * a fresh BroadcastReceiver every time the Activity was recreated (e.g.
 * rotation) and never unregistered the old one. The receiver is an
 * anonymous class that holds a strong reference to the outer
 * DownloadManager, so each unreleased receiver pinned the old Activity
 * and the old OkHttp call scope. As a singleton we register once and own
 * one scope. The [appContext] we keep is always the application
 * Context so the lifetime is the process lifetime, not the Activity.
 */
object DownloadManager {

    private const val TAG = "DownloadManager"

    @Volatile
    private var appContext: Context? = null
    private var androidDownloadManager: AndroidDownloadManager? = null
    private var scope: CoroutineScope? = null
    @Volatile
    private var registered = false

    /** In-flight OkHttp direct downloads keyed by Room record id. */
    private val directDownloadJobs = ConcurrentHashMap<Long, Job>()

    /** In-memory progress stream. UI subscribes for partial row updates. */
    private val _progressUpdates = kotlinx.coroutines.flow.MutableSharedFlow<
        com.dashensou.app.service.DownloadProgress>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val progressUpdates: kotlinx.coroutines.flow.SharedFlow<com.dashensou.app.service.DownloadProgress> = _progressUpdates

    /** Latest in-flight progress per record id — keeps rows visually correct
     *  after an Activity rebuild / tab switch (the SharedFlow is hot and does
     *  not replay history). */
    private val activeProgressSnapshot = ConcurrentHashMap<Long, com.dashensou.app.service.DownloadProgress>()

    /** Returns the most recently observed progress for an actively-downloading
     *  record. Returns null when the record isn't currently in flight. */
    fun peekProgress(recordId: Long): com.dashensou.app.service.DownloadProgress? =
        activeProgressSnapshot[recordId]

    /**
     * Initialise the singleton. Idempotent — safe to call from
     * Application.onCreate and from the first Activity that needs it.
     * Subsequent calls are no-ops.
     */
    @Synchronized
    fun init(context: Context) {
        if (registered) return
        val app = context.applicationContext
        appContext = app
        androidDownloadManager = app.getSystemService(Context.DOWNLOAD_SERVICE) as AndroidDownloadManager
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        registerReceiver(app)
        registered = true
        Log.i(TAG, "DownloadManager initialised (process-singleton)")
    }

    /**
     * Cancel every in-flight download coroutine. We do NOT call
     * this from a normal Activity lifecycle — the process-wide scope
     * is the right lifetime for "I started this download in the
     * background, it should finish even if the user closes the
     * Activity". Tests / instrumentation that need a clean teardown
     * are the primary caller.
     */
    @Synchronized
    fun shutdown() {
        scope?.cancel()
        scope = null
        registered = false
        appContext?.let { runCatching { it.unregisterReceiver(downloadCompleteReceiver) } }
        appContext = null
        androidDownloadManager = null
    }

    /**
     * Enqueue a download when we already know the direct URL (e.g. the search
     * source resolved a pan.baidu link, or aiqu225 already gave us a .txt
     * mirror). This bypasses the WebView intermediate page entirely.
     *
     * Implementation note: starting with Android 10 scoped storage, the
     * system's DownloadManager cannot write into Download/<subdir>/ for an
     * app that doesn't hold MANAGE_EXTERNAL_STORAGE. To get a stable
     * "Download/Book/xxx" landing path on Android 10+ we hand the bytes
     * through OkHttp and persist them via MediaStore.Downloads, which is
     * the only API that can still create the subfolder through scoped
     * storage without elevated permissions.
     *
     * [fileType] (when non-null) is the extension hint that came from the
     * search source — e.g. "epub" / "pdf" / "mobi". It's preferred over
     * the URL-extension guess below because a short-link URL or a content
     * distribution host might not actually contain the real extension in
     * its path.
     */
    fun enqueueDirectDownload(
        title: String,
        url: String,
        category: ResourceCategory,
        fileType: String? = null
    ) {
        val ctx = appContext ?: run {
            Log.w(TAG, "enqueueDirectDownload called before init()")
            return
        }
        val currentScope = scope ?: run {
            Log.w(TAG, "enqueueDirectDownload called after shutdown()")
            return
        }
        currentScope.launch {
            val fileName = getFileName(title, url, fileType)
            val subDir = subDirFor(category)
            val finalPath = MediaStorePaths.recordPath(subDir, fileName)

            Log.i(TAG, "enqueueDirectDownload: title=$title url=$url subDir=$subDir fileName=$fileName")

            val recordId = App.database.downloadRecordDao().insertDownloadRecord(
                DownloadRecord(
                    title = title,
                    url = url,
                    filePath = finalPath,
                    fileSize = 0,
                    downloadSize = 0,
                    status = DownloadStatus.DOWNLOADING,
                    downloadTime = System.currentTimeMillis(),
                    netDiskType = NetDiskType.DIRECT_URL,
                    category = category,
                    downloadId = 0L
                )
            )
            directDownloadJobs[recordId] = coroutineContext[Job]!!

            val record = App.database.downloadRecordDao().getDownloadRecordById(recordId) ?: return@launch
            val privateFilePath = ProgressDownloader.getFilePath(ctx, title, fileType ?: "")

            try {
                // 关键：下载中只推内存进度流，不写数据库（避免整行 rebind 闪烁）
                val ok = ProgressDownloader.download(
                    context = ctx,
                    record = record.copy(filePath = privateFilePath)
                ) { progress ->
                    activeProgressSnapshot[recordId] = progress
                    _progressUpdates.tryEmit(progress)
                }

                // 下载完成：写一次最终状态到数据库（触发 UI 更新一次）
                activeProgressSnapshot.remove(recordId)
                Log.i(TAG, "enqueueDirectDownload: result=$ok")
                val dao = App.database.downloadRecordDao()
                val current = dao.getDownloadRecordById(recordId) ?: return@launch
                if (current.status == DownloadStatus.PAUSED) return@launch

                if (ok) {
                    val finalFile = java.io.File(privateFilePath)
                    dao.updateDownloadRecord(
                        current.copy(
                            status = DownloadStatus.COMPLETED,
                            filePath = privateFilePath,
                            fileSize = finalFile.length(),
                            downloadSize = finalFile.length()
                        )
                    )
                } else {
                    ProgressDownloader.deleteDownloadedFile(ctx, privateFilePath)
                    dao.updateDownloadRecord(current.copy(status = DownloadStatus.FAILED))
                }
            } catch (e: CancellationException) {
                activeProgressSnapshot.remove(recordId)
                val dao = App.database.downloadRecordDao()
                val current = dao.getDownloadRecordById(recordId)
                if (current?.status == DownloadStatus.DOWNLOADING) {
                    dao.updateDownloadRecord(current.copy(status = DownloadStatus.PAUSED))
                }
                ProgressDownloader.deleteDownloadedFile(ctx, privateFilePath)
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "enqueueDirectDownload: download body failed", e)
                activeProgressSnapshot.remove(recordId)
                val dao = App.database.downloadRecordDao()
                val current = dao.getDownloadRecordById(recordId) ?: return@launch
                if (current.status != DownloadStatus.PAUSED) {
                    ProgressDownloader.deleteDownloadedFile(ctx, privateFilePath)
                    dao.updateDownloadRecord(current.copy(status = DownloadStatus.FAILED))
                }
            } finally {
                directDownloadJobs.remove(recordId)
            }
        }
    }

    /** Cancel an in-process OkHttp direct download (pause / delete). */
    fun cancelDirectDownload(recordId: Long) {
        directDownloadJobs[recordId]?.cancel()
    }

    private fun subDirFor(category: ResourceCategory): String = when (category) {
        ResourceCategory.EBOOK -> "Book"
        ResourceCategory.MOVIE -> "Movie"
        ResourceCategory.TV -> "TV"
        else -> "Other"
    }

    /**
     * Pause an in-flight system-DownloadManager download. We don't try to
     * mark the DB row PAUSED directly — the DownloadProgressPoller will
     * observe the cancelled status in its next tick and reconcile. The
     * records that go through [enqueueDirectDownload] carry
     * `downloadId = 0`, in which case the system DM has no id to cancel;
     * the in-process coroutine in [DirectDownloader] keeps running and
     * the row stays DOWNLOADING until it finishes (or the user kills
     * the app). That's an honest trade-off: re-implementing a
     * OkHttp-call-level pause for the in-process path is a much larger
     * change than the user actually wants.
     */
    fun cancelDownload(downloadId: Long) {
        if (downloadId <= 0) return
        val dm = androidDownloadManager ?: run {
            Log.w(TAG, "cancelDownload called before init()")
            return
        }
        runCatching { dm.remove(downloadId) }
            .onFailure { Log.w(TAG, "androidDownloadManager.remove($downloadId) failed", it) }
    }

    private fun getFileName(title: String, url: String, fileType: String? = null): String {
        val cleaned = title.replace("[^a-zA-Z0-9\\u4e00-\\u9fa5.-]".toRegex(), "_")
        val baseName = if (cleaned.length > 50) cleaned.substring(0, 50) else cleaned

        // 1) Trust the fileType hint from the search source first — it's the
        //    most reliable signal (e.g. "epub", "pdf", "mobi", "video", "zip").
        val knownExtensions = listOf(
            "epub", "mobi", "azw3", "pdf", "txt",
            "zip", "rar", "7z", "archive",
            "mp4", "mkv", "avi", "rmvb", "ts", "mov", "flv",
            "mp3", "m4a"
        )
        val extFromHint = fileType?.lowercase()?.takeIf { it in knownExtensions }
        if (extFromHint != null) {
            return baseName + "." + extFromHint
        }

        // 2) Otherwise parse the URL's *path* (query string ignored) to find
        //    the real extension. We deliberately do NOT scan the full URL —
        //    a CDN link like "...?type=txtbook&id=zip-1" must not be mistaken
        //    for a .txt file just because ".txt" appears in the query.
        val extFromPath = url.substringBefore('?')
            .substringBefore('#')
            .substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotBlank() && it in knownExtensions }
        if (extFromPath != null) {
            return baseName + "." + extFromPath
        }

        // 3) Last resort: no extension info, leave it as `.download` so the
        //    user / system can rename later. Never guess.
        return baseName + ".download"
    }

    private fun registerReceiver(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                context,
                downloadCompleteReceiver,
                IntentFilter(AndroidDownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                downloadCompleteReceiver,
                IntentFilter(AndroidDownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /**
     * The receiver is held in a singleton field (not a captured local)
     * so [shutdown] can call [Context.unregisterReceiver] against the
     * same instance. Anonymous-class receivers in the previous design
     * couldn't be unregistered.
     */
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val downloadId = intent?.getLongExtra(AndroidDownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
            if (downloadId == -1L) return

            val dm = androidDownloadManager ?: return
            val currentScope = scope ?: return

            val cursor = try {
                dm.query(AndroidDownloadManager.Query().setFilterById(downloadId))
            } catch (e: Exception) {
                Log.w(TAG, "query($downloadId) failed", e)
                return
            } ?: return

            cursor.use { c ->
                if (!c.moveToFirst()) return
                val status = c.getInt(c.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_STATUS))
                val bytesDownloaded = c.getLong(c.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val bytesTotal = c.getLong(c.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                currentScope.launch {
                    App.database.downloadRecordDao().getDownloadRecordByDownloadId(downloadId)?.let { record ->
                        val newStatus = when (status) {
                            AndroidDownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                            AndroidDownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                            AndroidDownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                            else -> record.status
                        }
                        App.database.downloadRecordDao().updateDownloadRecord(
                            record.copy(
                                status = newStatus,
                                downloadSize = bytesDownloaded,
                                fileSize = bytesTotal
                            )
                        )
                    }
                }
            }
        }
    }

    enum class TorrentOpenResult {
        SUCCESS,
        NOT_INSTALLED,
        OPEN_FAILED
    }

    /** magnet / ed2k — Quark Browser supports offline download via direct or quark:// URI. */
    fun openTorrentInQuark(url: String): TorrentOpenResult {
        val ctx = appContext ?: run {
            Log.w(TAG, "openTorrentInQuark called before init()")
            return TorrentOpenResult.OPEN_FAILED
        }
        if (!com.dashensou.app.util.UrlKinds.isTorrentLike(url)) {
            return TorrentOpenResult.OPEN_FAILED
        }
        val trimmed = url.trim()
        val installedPkgs = NetDiskUtils.QUARK_PACKAGE_CANDIDATES.filter { isAppInstalled(ctx, it) }
        if (installedPkgs.isEmpty()) {
            Log.w(TAG, "openTorrentInQuark: no Quark package found among ${NetDiskUtils.QUARK_PACKAGE_CANDIDATES}")
            return TorrentOpenResult.NOT_INSTALLED
        }
        Log.i(TAG, "openTorrentInQuark: installed=$installedPkgs url=${trimmed.take(80)}")

        val urisToTry = listOf(trimmed, NetDiskUtils.buildQuarkSchemeUrl(trimmed))
        for (pkg in installedPkgs) {
            for (uri in urisToTry) {
                if (startViewIntent(ctx, uri, pkg)) {
                    Log.i(TAG, "openTorrentInQuark success: uri=$uri pkg=$pkg")
                    return TorrentOpenResult.SUCCESS
                }
            }
        }
        for (uri in urisToTry) {
            if (startViewChooser(ctx, uri, "用夸克打开")) {
                Log.i(TAG, "openTorrentInQuark chooser success: uri=$uri")
                return TorrentOpenResult.SUCCESS
            }
        }
        Log.w(TAG, "openTorrentInQuark: all strategies failed")
        return TorrentOpenResult.OPEN_FAILED
    }

    fun isQuarkInstalled(): Boolean {
        val ctx = appContext ?: return false
        return NetDiskUtils.QUARK_PACKAGE_CANDIDATES.any { isAppInstalled(ctx, it) }
    }

    /**
     * Open an HTTP(S) direct URL in Quark Browser. Quark will detect the
     * downloadable resource via its built-in sniffer and offer the user
     * a much faster offline download. The point of this function is to
     * bypass the small, rate-limited public CDNs the search aggregators
     * return (typically a few KB/s) and let Quark's CDN pull the file
     * instead — usually 5-10x faster on the same link.
     */
    fun openHttpInQuark(url: String): Boolean {
        val ctx = appContext ?: run {
            Log.w(TAG, "openHttpInQuark called before init()")
            return false
        }
        val trimmed = url.trim()
        if (trimmed.isBlank() || !trimmed.startsWith("http")) return false
        val installedPkgs = NetDiskUtils.QUARK_PACKAGE_CANDIDATES.filter { isAppInstalled(ctx, it) }
        if (installedPkgs.isEmpty()) {
            Log.w(TAG, "openHttpInQuark: Quark not installed")
            return false
        }
        // Try a targeted VIEW first (lands directly in Quark with the
        // resource sniffer active), then fall back to the system chooser.
        for (pkg in installedPkgs) {
            if (startViewIntent(ctx, trimmed, pkg)) {
                Log.i(TAG, "openHttpInQuark success: pkg=$pkg")
                return true
            }
        }
        if (startViewChooser(ctx, trimmed, "用夸克下载")) return true
        Log.w(TAG, "openHttpInQuark: all strategies failed")
        return false
    }

    private fun startViewIntent(ctx: Context, uri: String, packageName: String): Boolean {
        if (uri.isBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setPackage(packageName)
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.d(TAG, "startViewIntent failed: uri=$uri pkg=$packageName (${e.message})")
            false
        }
    }

    private fun startViewChooser(ctx: Context, uri: String, title: String): Boolean {
        if (uri.isBlank()) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val chooser = Intent.createChooser(intent, title)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
            true
        } catch (e: Exception) {
            Log.d(TAG, "startViewChooser failed: uri=$uri (${e.message})")
            false
        }
    }

    fun openNetDiskApp(result: SearchResult): Boolean {
        val ctx = appContext ?: run {
            Log.w(TAG, "openNetDiskApp called before init()")
            return false
        }
        val packageName = NetDiskUtils.getNetDiskPackageName(result.netDiskType)
        Log.i(TAG, "openNetDiskApp: type=${result.netDiskType} pkg=$packageName url=${result.url}")

        if (packageName == null) {
            Log.w(TAG, "no package mapping for type=${result.netDiskType}")
            return openByChooser(ctx, result)
        }

        if (isAppInstalled(ctx, packageName)) {
            Log.i(TAG, "package installed: $packageName, trying to open directly with URI")

            if (openBySchemeWithPackage(ctx, result, packageName)) {
                return true
            }

            if (openByChooserWithPackage(ctx, result, packageName)) {
                return true
            }
        } else {
            Log.w(TAG, "package not installed: $packageName")
        }

        return openByChooser(ctx, result)
    }

    private fun isAppInstalled(ctx: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "isAppInstalled check failed: $packageName", e)
            false
        }
    }

    private fun openBySchemeWithPackage(ctx: Context, result: SearchResult, packageName: String): Boolean {
        val schemeUri = NetDiskUtils.buildNetDiskIntentUrl(result.url, result.netDiskType)
        if (schemeUri == result.url) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(schemeUri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setPackage(packageName)
            ctx.startActivity(intent)
            Log.i(TAG, "openBySchemeWithPackage success: $schemeUri pkg=$packageName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "openBySchemeWithPackage failed: $schemeUri pkg=$packageName", e)
            false
        }
    }

    private fun openByChooserWithPackage(ctx: Context, result: SearchResult, packageName: String): Boolean {
        if (result.url.isBlank() || !result.url.startsWith("http")) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setPackage(packageName)
            ctx.startActivity(intent)
            Log.i(TAG, "openByChooserWithPackage success: ${result.url} pkg=$packageName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "openByChooserWithPackage failed", e)
            false
        }
    }

    private fun openByChooser(ctx: Context, result: SearchResult): Boolean {
        if (result.url.isBlank() || !result.url.startsWith("http")) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val chooser = Intent.createChooser(intent, "用网盘打开")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(chooser)
            Log.i(TAG, "openByChooser success: ${result.url}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "openByChooser failed", e)
            false
        }
    }
}
