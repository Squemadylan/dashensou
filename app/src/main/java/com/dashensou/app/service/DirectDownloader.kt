package com.dashensou.app.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.dashensou.app.net.HttpClient
import com.dashensou.app.util.FileTypes
import com.dashensou.app.util.MediaStorePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * Downloads a file with OkHttp and persists it into the public
 * Download/<subDir>/ folder using MediaStore. This works on Android 10+
 * scoped storage without requiring MANAGE_EXTERNAL_STORAGE.
 *
 * Returns true on success, false on any failure. Cooperative cancellation
 * propagates from the caller's coroutine through [HttpClient.execute].
 */
object DirectDownloader {
    private const val TAG = "DirectDownloader"
    private const val DOWNLOAD_TIMEOUT_MS = 60_000L
    private const val MAX_BYTES = 512L * 1024 * 1024 // 512 MiB safety cap

    suspend fun download(
        context: Context,
        url: String,
        displayName: String,
        subDir: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = HttpClient.newGet(url)
            val response = HttpClient.execute(request, perCallTimeoutMs = DOWNLOAD_TIMEOUT_MS)
                ?: return@withContext false
            response.use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "HTTP ${resp.code} for $url")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val contentLength = body.contentLength()
                if (contentLength > MAX_BYTES) {
                    Log.e(TAG, "refusing download > $MAX_BYTES bytes: $contentLength")
                    return@withContext false
                }

                val resolver = context.contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(
                            MediaStore.MediaColumns.RELATIVE_PATH,
                            MediaStorePaths.relativePath(subDir)
                        )
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    if (contentLength > 0) {
                        put(MediaStore.MediaColumns.SIZE, contentLength)
                    }
                    put(MediaStore.MediaColumns.MIME_TYPE, FileTypes.mimeTypeForFileName(displayName))
                }

                val uri: Uri? = try {
                    resolver.insert(collection, values)
                } catch (e: Exception) {
                    Log.e(TAG, "MediaStore.insert threw for $displayName", e)
                    return@withContext false
                }
                if (uri == null) {
                    Log.e(TAG, "MediaStore.insert returned null for $displayName")
                    return@withContext false
                }
                Log.i(TAG, "inserted uri=$uri for ${MediaStorePaths.recordPath(subDir, displayName)}")

                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        body.byteStream().use { input ->
                            writeWithCap(input, out, contentLength)
                        }
                    } ?: run {
                        resolver.delete(uri, null, null)
                        return@withContext false
                    }
                } catch (e: CancellationException) {
                    resolver.delete(uri, null, null)
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "write body failed", e)
                    resolver.delete(uri, null, null)
                    return@withContext false
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }

                Log.i(TAG, "saved $displayName (${contentLength}B) to Downloads/$subDir")
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "download failed for $url", e)
            false
        }
    }

    private fun writeWithCap(
        input: java.io.InputStream,
        out: OutputStream,
        declaredLength: Long
    ) {
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_BYTES) {
                throw IOException("download exceeds $MAX_BYTES bytes")
            }
            out.write(buffer, 0, read)
        }
        out.flush()
        if (declaredLength > 0 && total != declaredLength) {
            Log.w(TAG, "size mismatch: declared=$declaredLength written=$total")
        }
    }

}
