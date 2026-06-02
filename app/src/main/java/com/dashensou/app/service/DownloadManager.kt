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
import java.io.File
import androidx.core.content.ContextCompat
import com.dashensou.app.App
import com.dashensou.app.data.model.DownloadRecord
import com.dashensou.app.data.model.DownloadStatus
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.util.NetDiskUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"
    }

    private val androidDownloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as AndroidDownloadManager
    private var downloadIdMap = mutableMapOf<Long, Long>()

    init {
        registerReceiver()
    }

    fun downloadFile(result: SearchResult, category: ResourceCategory) {
        val fileName = getFileName(result.title, result.url)
        val subDir = when (category) {
            ResourceCategory.EBOOK -> "book"
            ResourceCategory.MOVIE -> "movie"
            ResourceCategory.TV -> "tv"
            else -> "other"
        }

        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val targetDir = File(baseDir, "DaShenSou/$subDir")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val targetFile = File(targetDir, fileName)
        val absolutePath = targetFile.absolutePath

        val request = AndroidDownloadManager.Request(Uri.parse(result.url))
            .setTitle(fileName)
            .setDescription(result.description)
            .setDestinationUri(Uri.fromFile(targetFile))
            .setNotificationVisibility(AndroidDownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadId = try {
            androidDownloadManager.enqueue(request)
        } catch (e: Exception) {
            Log.e(TAG, "enqueue failed, fallback to external public dir", e)
            return downloadFileFallback(result, category, fileName, subDir)
        }

        val record = DownloadRecord(
            title = result.title,
            url = result.url,
            filePath = absolutePath,
            fileSize = 0,
            downloadSize = 0,
            status = DownloadStatus.DOWNLOADING,
            downloadTime = System.currentTimeMillis(),
            netDiskType = result.netDiskType,
            category = category
        )

        val id = runBlocking(Dispatchers.IO) {
            App.database.downloadRecordDao().insertDownloadRecord(record.copy(downloadId = downloadId))
        }
        downloadIdMap[downloadId] = id
    }

    fun getDownloadProgress(downloadId: Long): Int {
        val query = AndroidDownloadManager.Query().setFilterById(downloadId)
        val cursor = androidDownloadManager.query(query)
        return if (cursor.moveToFirst()) {
            val bytesDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val bytesTotal = cursor.getInt(cursor.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            cursor.close()
            if (bytesTotal > 0) (bytesDownloaded * 100 / bytesTotal) else 0
        } else {
            cursor.close()
            0
        }
    }

    fun cancelDownload(downloadId: Long) {
        androidDownloadManager.remove(downloadId)
        CoroutineScope(Dispatchers.IO).launch {
            downloadIdMap[downloadId]?.let { recordId ->
                val record = App.database.downloadRecordDao().getDownloadRecordById(recordId)
                record?.let {
                    App.database.downloadRecordDao().updateDownloadRecord(it.copy(status = DownloadStatus.PAUSED))
                }
            }
        }
    }

    fun queryAndUpdateProgress() {
        CoroutineScope(Dispatchers.IO).launch {
            val allRecords = App.database.downloadRecordDao().getAllDownloadRecords()
            allRecords.collect { records ->
                for (record in records) {
                    if (record.downloadId > 0 && record.status == DownloadStatus.DOWNLOADING) {
                        val progress = getDownloadProgress(record.downloadId)
                        if (progress >= 0) {
                            val query = AndroidDownloadManager.Query().setFilterById(record.downloadId)
                            val cursor = androidDownloadManager.query(query)
                            if (cursor.moveToFirst()) {
                                val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                cursor.close()
                                App.database.downloadRecordDao().updateDownloadRecord(
                                    record.copy(downloadSize = bytesDownloaded, fileSize = bytesTotal)
                                )
                            } else {
                                cursor.close()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun getFileName(title: String, url: String): String {
        val cleaned = title.replace("[^a-zA-Z0-9\\u4e00-\\u9fa5.-]".toRegex(), "_")
        val baseName = if (cleaned.length > 50) cleaned.substring(0, 50) else cleaned
        
        var extension = ".download"
        val lowerUrl = url.lowercase()
        val knownExtensions = listOf(".zip", ".rar", ".7z", ".pdf", ".epub", ".mobi", ".azw3", ".txt", ".mp4", ".mkv", ".avi", ".rmvb", ".ts", ".mov", ".flv", ".mp3")
        for (ext in knownExtensions) {
            if (lowerUrl.contains(ext)) {
                extension = ext
                break
            }
        }
        return baseName + extension
    }

    private fun downloadFileFallback(
        result: SearchResult,
        category: ResourceCategory,
        fileName: String,
        subDir: String
    ) {
        val standardDir = Environment.DIRECTORY_DOWNLOADS
        try {
            val request = AndroidDownloadManager.Request(Uri.parse(result.url))
                .setTitle(fileName)
                .setDescription(result.description)
                .setDestinationInExternalPublicDir(standardDir, "DaShenSou_${subDir}_$fileName")
                .setNotificationVisibility(AndroidDownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

            val downloadId = androidDownloadManager.enqueue(request)

            val record = DownloadRecord(
                title = result.title,
                url = result.url,
                filePath = "$standardDir/DaShenSou_${subDir}_$fileName",
                fileSize = 0,
                downloadSize = 0,
                status = DownloadStatus.DOWNLOADING,
                downloadTime = System.currentTimeMillis(),
                netDiskType = result.netDiskType,
                category = category
            )

            val id = runBlocking(Dispatchers.IO) {
                App.database.downloadRecordDao().insertDownloadRecord(record)
            }
            downloadIdMap[downloadId] = id
            Log.i(TAG, "fallback enqueue success, id=$downloadId")
        } catch (e: Exception) {
            Log.e(TAG, "fallback enqueue also failed for ${result.url}", e)
        }
    }

    private fun registerReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val downloadId = intent?.getLongExtra(AndroidDownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
                if (downloadId == -1L) return

                val query = AndroidDownloadManager.Query().setFilterById(downloadId)
                val cursor = androidDownloadManager.query(query)
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_STATUS))
                    val recordId = downloadIdMap[downloadId]

                    CoroutineScope(Dispatchers.IO).launch {
                        recordId?.let { id ->
                            val record = App.database.downloadRecordDao().getDownloadRecordById(id)
                            record?.let {
                                val newStatus = when (status) {
                                    AndroidDownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                                    AndroidDownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                                    AndroidDownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                                    else -> DownloadStatus.DOWNLOADING
                                }
                                App.database.downloadRecordDao().updateDownloadRecord(it.copy(status = newStatus))
                            }
                        }
                    }
                }
                cursor.close()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                context,
                receiver,
                IntentFilter(AndroidDownloadManager.ACTION_DOWNLOAD_COMPLETE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(receiver, IntentFilter(AndroidDownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    fun openNetDiskApp(result: SearchResult): Boolean {
        val packageName = NetDiskUtils.getNetDiskPackageName(result.netDiskType)
        Log.i(TAG, "openNetDiskApp: type=${result.netDiskType} pkg=$packageName url=${result.url}")

        if (packageName == null) {
            Log.w(TAG, "no package mapping for type=${result.netDiskType}")
            return openByChooser(result)
        }

        if (isAppInstalled(packageName)) {
            Log.i(TAG, "package installed: $packageName, trying to open directly with URI")

            if (openBySchemeWithPackage(result, packageName)) {
                return true
            }

            if (openByChooserWithPackage(result, packageName)) {
                return true
            }
        } else {
            Log.w(TAG, "package not installed: $packageName")
        }

        return openByChooser(result)
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "isAppInstalled check failed: $packageName", e)
            false
        }
    }

    private fun openByScheme(result: SearchResult): Boolean {
        val schemeUri = NetDiskUtils.buildNetDiskIntentUrl(result.url, result.netDiskType)
        if (schemeUri == result.url) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(schemeUri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "openByScheme success: $schemeUri")
            true
        } catch (e: Exception) {
            Log.w(TAG, "openByScheme failed: $schemeUri", e)
            false
        }
    }

    private fun openByChooser(result: SearchResult): Boolean {
        if (result.url.isBlank() || !result.url.startsWith("http")) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val chooser = Intent.createChooser(intent, "用网盘打开")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            Log.i(TAG, "openByChooser success: ${result.url}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "openByChooser failed", e)
            false
        }
    }

    private fun openBySchemeWithPackage(result: SearchResult, packageName: String): Boolean {
        val schemeUri = NetDiskUtils.buildNetDiskIntentUrl(result.url, result.netDiskType)
        if (schemeUri == result.url) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(schemeUri))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setPackage(packageName)
            context.startActivity(intent)
            Log.i(TAG, "openBySchemeWithPackage success: $schemeUri pkg=$packageName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "openBySchemeWithPackage failed: $schemeUri pkg=$packageName", e)
            false
        }
    }

    private fun openByChooserWithPackage(result: SearchResult, packageName: String): Boolean {
        if (result.url.isBlank() || !result.url.startsWith("http")) return false
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(result.url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.setPackage(packageName)
            context.startActivity(intent)
            Log.i(TAG, "openByChooserWithPackage success: ${result.url} pkg=$packageName")
            true
        } catch (e: Exception) {
            Log.w(TAG, "openByChooserWithPackage failed", e)
            false
        }
    }
}
