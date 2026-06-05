package com.dashensou.app.service

import android.app.DownloadManager as AndroidDownloadManager
import android.content.Context
import android.util.Log
import com.dashensou.app.App
import com.dashensou.app.data.model.DownloadRecord
import com.dashensou.app.data.model.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-wide download progress poller.
 *
 * Originally MainActivity.startProgressPolling ran a 2s tick on
 * lifecycleScope while the downloads tab was visible. That meant any
 * DOWNLOADING record's downloadSize / fileSize froze at the value
 * recorded at insert time as soon as the user navigated to search or
 * history. The UI only ever caught up to the next tick, which could be
 * minutes later.
 *
 * This poller lives in an Application-scoped SupervisorJob so the
 * Android DownloadManager query keeps running no matter which tab the
 * user is on. The UI is a pure Flow consumer over DownloadRecordDao; it
 * doesn't own the polling lifecycle anymore.
 *
 * P0#robustness: this is a process-wide singleton — the previous
 * per-Application-instance version re-bound a new SupervisorJob on every
 * `start()` call (and there's only one Application instance so this
 * usually didn't matter, but instrumented tests / Robolectric spun up
 * a new App per test and leaked the previous scope). As an object the
 * scope is created once per process and [stop] is the only path that
 * tears it down.
 */
object DownloadProgressPoller {

    private const val TAG = "DownloadProgressPoller"
    private const val TICK_MS = 2_000L

    @Volatile
    private var scope: CoroutineScope? = null
    @Volatile
    private var androidDownloadManager: AndroidDownloadManager? = null

    /**
     * Start the polling loop. Idempotent — safe to call more than once
     * (subsequent calls are no-ops while a scope is active). Always
     * called from [com.dashensou.app.App.onCreate].
     */
    @Synchronized
    fun start(context: Context) {
        if (scope?.isActive == true) return
        val dm = context.applicationContext
            .getSystemService(Context.DOWNLOAD_SERVICE) as AndroidDownloadManager
        androidDownloadManager = dm
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch {
            Log.i(TAG, "start: polling download progress every ${TICK_MS}ms")
            while (isActive) {
                try {
                    tick(dm)
                } catch (e: Exception) {
                    Log.w(TAG, "tick failed", e)
                }
                delay(TICK_MS)
            }
        }
    }

    /**
     * Cancel the polling loop. Used by tests / instrumentation; the
     * production lifetime is the process lifetime.
     */
    @Synchronized
    fun stop() {
        scope?.cancel()
        scope = null
        androidDownloadManager = null
        Log.i(TAG, "stop: polling cancelled")
    }

    private suspend fun tick(dm: AndroidDownloadManager) {
        val dao = App.database.downloadRecordDao()
        val records = dao.getAllDownloadRecords().first()
        for (record in records) {
            if (record.downloadId > 0 && record.status == DownloadStatus.DOWNLOADING) {
                updateSingleRecordProgress(dm, record)
            }
        }
    }

    private fun updateSingleRecordProgress(dm: AndroidDownloadManager, record: DownloadRecord) {
        val currentScope = scope ?: return
        val query = AndroidDownloadManager.Query().setFilterById(record.downloadId)
        val cursor = try {
            dm.query(query)
        } catch (e: Exception) {
            Log.w(TAG, "query(${record.downloadId}) failed", e)
            return
        } ?: return

        cursor.use { c ->
            if (!c.moveToFirst()) return
            val bytesDownloaded = c.getLong(
                c.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            )
            val bytesTotal = c.getLong(
                c.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            )
            val status = c.getInt(
                c.getColumnIndexOrThrow(AndroidDownloadManager.COLUMN_STATUS)
            )
            val newStatus = when (status) {
                AndroidDownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.COMPLETED
                AndroidDownloadManager.STATUS_FAILED -> DownloadStatus.FAILED
                AndroidDownloadManager.STATUS_PAUSED -> DownloadStatus.PAUSED
                else -> record.status
            }
            currentScope.launch {
                App.database.downloadRecordDao().updateDownloadRecord(
                    record.copy(
                        downloadSize = bytesDownloaded,
                        fileSize = bytesTotal,
                        status = newStatus
                    )
                )
            }
        }
    }
}
