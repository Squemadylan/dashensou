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
import kotlinx.coroutines.withContext

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
            viewModelScope.launch(Dispatchers.IO) {
                App.database.downloadRecordDao().updateDownloadRecord(
                    record.copy(status = DownloadStatus.PAUSED)
                )
            }
        } else if (record.netDiskType == NetDiskType.DIRECT_URL && record.id > 0) {
            downloadManager.cancelDirectDownload(record.id)
            viewModelScope.launch(Dispatchers.IO) {
                val dao = App.database.downloadRecordDao()
                val current = dao.getDownloadRecordById(record.id) ?: return@launch
                if (current.status == DownloadStatus.DOWNLOADING) {
                    dao.updateDownloadRecord(current.copy(status = DownloadStatus.PAUSED))
                }
            }
        }
    }

    fun resume(record: DownloadRecord) {
        if (record.netDiskType == NetDiskType.DIRECT_URL) {
            restartDirectDownload(record)
        } else {
            retry(record)
        }
    }

    fun retry(record: DownloadRecord) {
        if (record.netDiskType == NetDiskType.DIRECT_URL) {
            restartDirectDownload(record)
        } else {
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
                } else if (record.id > 0) {
                    downloadManager.cancelDirectDownload(record.id)
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

    private fun restartDirectDownload(record: DownloadRecord) {
        viewModelScope.launch(Dispatchers.IO) {
            App.database.downloadRecordDao().deleteDownloadRecord(record)
            withContext(Dispatchers.Main) {
                downloadManager.enqueueDirectDownload(
                    title = record.title,
                    url = record.url,
                    category = record.category,
                    fileType = null
                )
            }
        }
    }

    private companion object {
        private const val TAG = "DownloadViewModel"
    }
}
