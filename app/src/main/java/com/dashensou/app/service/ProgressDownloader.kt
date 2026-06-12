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
import java.net.URL
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
        // Use the file extension hint from the search source when we have one
        // (it's preferred over guessing from the URL's path component, which
        // is often obfuscated on short-link / pan-search redirectors).
        val extHint = run {
            val u = record.url
            val q = u.indexOf('?')
            val pathNoQuery = if (q >= 0) u.substring(0, q) else u
            val dot = pathNoQuery.lastIndexOf('.')
            if (dot < 0 || dot < pathNoQuery.length - 8) "" else pathNoQuery.substring(dot + 1).lowercase()
        }
        val outputFile = resolveOutputFile(context, record.title, extHint)
        Log.i(TAG, "download start: title=${record.title} url=${record.url} extHint=$extHint target=${outputFile.absolutePath}")

        try {
            // Direct-URL ebook sources often serve bytes from a short-link
            // CDN that requires real-browser headers (UA, Referer, Accept).
            // Using HttpClient's minimal "DaShenSou/1.0" UA is what made
            // the server return an empty body and then close the socket.
            val request = buildBrowserLikeRequest(record.url)
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
                // Final success guard: bytes were written, the file exists,
                // and its on-disk size matches what we streamed. A 0-byte
                // file here would otherwise show up as "完成" with no
                // actual content — the symptom the user just hit.
                if (downloadedBytes <= 0L) {
                    Log.e(TAG, "download marked successful but 0 bytes streamed; treating as failure")
                    outputFile.delete()
                    return@withContext false
                }
                val onDiskLen = outputFile.length()
                if (onDiskLen <= 0L || (totalBytes > 0L && onDiskLen < totalBytes)) {
                    Log.e(TAG, "download size mismatch: streamed=$downloadedBytes onDisk=$onDiskLen total=$totalBytes; treating as failure")
                    outputFile.delete()
                    return@withContext false
                }
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
        return resolveOutputFile(context, title, extension).absolutePath
    }

    /**
     * Single source of truth for the on-disk path of a download.
     * Both the path stored in Room and the bytes-on-disk target MUST go
     * through this function — otherwise the row would point at a file
     * that was never written, and "完成" would silently be a 0-byte ghost.
     */
    private fun resolveOutputFile(context: Context, title: String, extension: String): File {
        val fileDir = File(context.filesDir, "downloads")
        if (!fileDir.exists()) {
            fileDir.mkdirs()
        }
        val safeFileName = title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5.-]"), "_")
            .take(50)
            .ifBlank { "download" }
        val ext = if (extension.isNotBlank() && extension.length <= 10) extension else "download"
        return File(fileDir, "$safeFileName.$ext")
    }

    fun deleteDownloadedFile(context: Context, filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            Log.w(TAG, "delete file failed: $filePath", e)
            false
        }
    }

    /**
     * Build a request that looks like a real mobile browser. The DIRECT_URL
     * ebook sources (Gutenberg mirrors, 52book's short-link CDNs, etc.)
     * inspect User-Agent and Referer; the "DaShenSou/1.0 (Android)" UA
     * we ship elsewhere trips a 403 / empty-body / "Socket closed" on a
     * meaningful chunk of hosts. Sending a real Chrome-on-Android UA
     * (plus a same-origin Referer when the URL is HTTPS) makes the same
     * URL return the actual bytes.
     */
    private fun buildBrowserLikeRequest(url: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Connection", "keep-alive")
            .get()
        // Same-origin Referer is a strong signal to anti-hotlink middlewares.
        try {
            val u = URL(url)
            val ref = "${u.protocol}://${u.host}/"
            builder.header("Referer", ref)
        } catch (_: Exception) {
            // Not a parseable URL — let the request go without Referer.
        }
        return builder.build()
    }
}
