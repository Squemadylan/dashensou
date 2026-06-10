package com.dashensou.app.service

import android.content.Context
import android.util.Log
import com.dashensou.app.data.model.DownloadRecord
import com.dashensou.app.data.model.DownloadStatus
import com.dashensou.app.net.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.coroutines.coroutineContext

data class DownloadProgress(
    val recordId: Long,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long
) {
    val progress: Int
        get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

    val speedFormatted: String
        get() {
            return when {
                speedBytesPerSec <= 0 -> ""
                speedBytesPerSec < 1024 -> "$speedBytesPerSec B/s"
                speedBytesPerSec < 1024 * 1024 -> "${speedBytesPerSec / 1024} KB/s"
                else -> String.format("%.1f MB/s", speedBytesPerSec / (1024.0 * 1024.0))
            }
        }

    val sizeFormatted: String
        get() {
            val downloadedMb = downloadedBytes / (1024.0 * 1024.0)
            val totalMb = if (totalBytes > 0) totalBytes / (1024.0 * 1024.0) else 0.0
            return when {
                totalBytes <= 0 -> String.format("%.1f MB", downloadedMb)
                else -> String.format("%.1f / %.1f MB", downloadedMb, totalMb)
            }
        }
}

interface ProgressCallback {
    fun onProgress(progress: DownloadProgress)
    fun onSuccess(filePath: String, fileSize: Long)
    fun onFailure(error: String)
}

object ProgressDownloader {
    private const val TAG = "ProgressDownloader"
    private const val BUFFER_SIZE = 8192
    private const val MAX_BYTES = 512L * 1024 * 1024
    private const val PROGRESS_UPDATE_INTERVAL = 500L
    private const val DOWNLOAD_TIMEOUT_MS = 5 * 60 * 1000L

    suspend fun download(
        context: Context,
        record: DownloadRecord,
        onProgress: (DownloadProgress) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val fileDir = File(context.filesDir, "downloads")
        if (!fileDir.exists()) {
            fileDir.mkdirs()
        }
        val safeFileName = record.title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5.-]"), "_")
            .take(50)
        val extension = record.filePath.substringAfterLast('.', "download")
        val fileName = if (extension.isNotBlank() && extension.length <= 10) {
            "$safeFileName.$extension"
        } else {
            "$safeFileName.download"
        }
        val outputFile = File(fileDir, fileName)

        try {
            val request = HttpClient.newGet(record.url)
            val response = HttpClient.execute(request, perCallTimeoutMs = DOWNLOAD_TIMEOUT_MS)
                ?: return@withContext false

            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "HTTP ${resp.code} for ${record.url}")
                    return@withContext false
                }

                val body = resp.body ?: return@withContext false
                val contentLength = body.contentLength()

                if (contentLength > MAX_BYTES) {
                    Log.e(TAG, "refusing download > $MAX_BYTES bytes: $contentLength")
                    return@withContext false
                }

                val totalBytes = if (contentLength > 0) contentLength else 0L
                var downloadedBytes = 0L
                var lastUpdateTime = System.currentTimeMillis()
                var lastBytes = 0L

                FileOutputStream(outputFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            coroutineContext.ensureActive()

                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val now = System.currentTimeMillis()
                            val elapsed = now - lastUpdateTime

                            if (elapsed >= PROGRESS_UPDATE_INTERVAL) {
                                val speed = ((downloadedBytes - lastBytes) * 1000L) / elapsed
                                onProgress(
                                    DownloadProgress(
                                        recordId = record.id,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        speedBytesPerSec = speed
                                    )
                                )
                                lastUpdateTime = now
                                lastBytes = downloadedBytes
                            }

                            if (downloadedBytes > MAX_BYTES) {
                                outputFile.delete()
                                Log.e(TAG, "download exceeds $MAX_BYTES bytes")
                                return@withContext false
                            }
                        }
                    }
                    output.flush()
                }

                val finalSpeed = if (downloadedBytes > 0 && System.currentTimeMillis() - lastUpdateTime > 0) {
                    (downloadedBytes * 1000L) / (System.currentTimeMillis() - lastUpdateTime)
                } else 0L

                onProgress(
                    DownloadProgress(
                        recordId = record.id,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        speedBytesPerSec = finalSpeed
                    )
                )

                Log.i(TAG, "download completed: ${outputFile.absolutePath} (${downloadedBytes}B)")
                true
            }
        } catch (e: CancellationException) {
            outputFile.delete()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "download failed", e)
            outputFile.delete()
            false
        }
    }

    fun getFilePath(context: Context, title: String, extension: String): String {
        val fileDir = File(context.filesDir, "downloads")
        val safeFileName = title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5.-]"), "_")
            .take(50)
        val ext = if (extension.isNotBlank() && extension.length <= 10) extension else "download"
        return File(fileDir, "$safeFileName.$ext").absolutePath
    }

    fun deleteDownloadedFile(context: Context, filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            Log.w(TAG, "delete file failed: $filePath", e)
            false
        }
    }
}
