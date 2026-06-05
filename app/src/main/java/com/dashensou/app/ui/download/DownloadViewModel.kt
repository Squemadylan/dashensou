package com.dashensou.app.ui.download

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dashensou.app.App
import com.dashensou.app.data.model.DownloadRecord
import com.dashensou.app.data.model.DownloadStatus
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.service.DownloadManager
import com.dashensou.app.util.FileOpener
import com.dashensou.app.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the downloads list and the row-level intents (pause / resume /
 * retry / open / delete). The list itself comes from Room as a Flow --
 * DownloadProgressPoller writes into the same table, so the UI here
 * never has to know about the polling lifecycle.
 *
 * P1#7: this used to be the bottom half of MainActivity; pulling it
 * out lets the Activity get out of the way of the row-action handlers.
 */
class DownloadViewModel(
    application: Application,
    private val downloadManager: DownloadManager
) : AndroidViewModel(application) {

    val records: StateFlow<List<DownloadRecord>> = App.database
        .downloadRecordDao()
        .getAllDownloadRecords()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun open(record: DownloadRecord) {
        val ok = FileOpener.open(getApplication(), record.filePath)
        if (!ok) {
            Log.w(TAG, "open: FileOpener returned false for ${record.filePath}")
        }
    }

    fun openDownloadsFolder() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                data = Uri.parse("content://media/external/downloads")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "openDownloadsFolder failed", e)
        }
    }

    fun pause(record: DownloadRecord) {
        if (record.downloadId > 0) {
            downloadManager.cancelDownload(record.downloadId)
        } else {
            // No system DownloadManager id (direct-URL path) -- flip the
            // status directly. The in-process coroutine keeps running,
            // but the UI honestly reflects the user's intent.
            viewModelScope.launch(Dispatchers.IO) {
                App.database.downloadRecordDao().updateDownloadRecord(
                    record.copy(status = DownloadStatus.PAUSED)
                )
            }
        }
    }

    fun resume(record: DownloadRecord) {
        if (record.netDiskType == NetDiskType.DIRECT_URL) {
            // The paused row was for an OkHttp-backed download; the
            // easiest correct path is to drop the row and start a fresh
            // download. Trying to rehydrate OkHttp call state would
            // require far more plumbing than this affordance is worth.
            viewModelScope.launch(Dispatchers.IO) {
                App.database.downloadRecordDao().deleteDownloadRecord(record)
            }
            downloadManager.enqueueDirectDownload(
                title = record.title,
                url = record.url,
                category = record.category,
                fileType = null
            )
        } else {
            retry(record)
        }
    }

    fun retry(record: DownloadRecord) {
        if (record.netDiskType == NetDiskType.DIRECT_URL) {
            viewModelScope.launch(Dispatchers.IO) {
                App.database.downloadRecordDao().deleteDownloadRecord(record)
            }
            downloadManager.enqueueDirectDownload(
                title = record.title,
                url = record.url,
                category = record.category,
                fileType = null
            )
        } else {
            // For non-direct records we re-open the installed net-disk
            // app with the original share URL. The user can re-grab the
            // file from there.
            val result = SearchResult(
                title = record.title,
                url = record.url,
                netDiskType = record.netDiskType,
                category = record.category
            )
            downloadManager.openNetDiskApp(result)
        }
    }

    fun delete(record: DownloadRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (record.downloadId > 0) {
                    downloadManager.cancelDownload(record.downloadId)
                }
                val uri = FileOpener.resolveUri(getApplication(), record.filePath)
                if (uri != null) {
                    runCatching {
                        getApplication<Application>().contentResolver.delete(uri, null, null)
                    }.onFailure { Log.w(TAG, "contentResolver.delete($uri) failed", it) }
                }
                FileUtils.deleteFile(record.filePath)
            } finally {
                App.database.downloadRecordDao().deleteDownloadRecord(record)
            }
        }
    }

    private companion object {
        private const val TAG = "DownloadViewModel"
    }
}
