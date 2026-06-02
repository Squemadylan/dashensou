package com.dashensou.app.service

import android.app.DownloadManager as AndroidDownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import com.dashensou.app.App
import com.dashensou.app.data.model.DownloadRecord
import com.dashensou.app.data.model.DownloadStatus
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DownloadManager(private val context: Context) {

    private val androidDownloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as AndroidDownloadManager
    private var downloadIdMap = mutableMapOf<Long, Long>()

    init {
        registerReceiver()
    }

    fun downloadFile(result: SearchResult, category: ResourceCategory) {
        val fileName = getFileName(result.title)
        val downloadDir = when (category) {
            ResourceCategory.EBOOK -> Environment.DIRECTORY_DOWNLOADS + "/DaShenSou/book"
            ResourceCategory.MOVIE -> Environment.DIRECTORY_DOWNLOADS + "/DaShenSou/movie"
            ResourceCategory.TV -> Environment.DIRECTORY_DOWNLOADS + "/DaShenSou/tv"
            else -> Environment.DIRECTORY_DOWNLOADS + "/DaShenSou/other"
        }

        val request = AndroidDownloadManager.Request(Uri.parse(result.url))
            .setTitle(fileName)
            .setDescription(result.description)
            .setDestinationInExternalPublicDir(downloadDir, fileName)
            .setNotificationVisibility(AndroidDownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)

        val downloadId = androidDownloadManager.enqueue(request)

        val record = DownloadRecord(
            title = result.title,
            url = result.url,
            filePath = downloadDir + "/" + fileName,
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

    private fun getFileName(title: String): String {
        val cleaned = title.replace("[^a-zA-Z0-9\\u4e00-\\u9fa5.-]".toRegex(), "_")
        return if (cleaned.length > 50) cleaned.substring(0, 50) + ".download" else cleaned + ".download"
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
        val packageName = when (result.netDiskType) {
            NetDiskType.BAIDU -> "com.baidu.netdisk"
            NetDiskType.QUARK -> "com.quark.browser"
            NetDiskType.XUNLEI -> "com.xunlei.downloadprovider"
            NetDiskType.ALIYUN -> "com.alicloud.databox"
            NetDiskType.YUNPAN123 -> "com.yunpan.www"
            else -> return false
        }

        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
