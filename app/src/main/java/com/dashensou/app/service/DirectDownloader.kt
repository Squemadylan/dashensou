package com.dashensou.app.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads a file with OkHttp and persists it into the public
 * Download/<subDir>/ folder using MediaStore. This works on Android 10+
 * scoped storage without requiring MANAGE_EXTERNAL_STORAGE.
 *
 * Returns true on success, false on any failure.
 */
object DirectDownloader {
    private const val TAG = "DirectDownloader"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun download(
        context: Context,
        url: String,
        displayName: String,
        subDir: String
    ): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code} for $url")
                    return false
                }
                val body = response.body ?: return false
                val contentLength = body.contentLength()

                val resolver = context.contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }

                val relative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "$subDir/$displayName"
                } else {
                    displayName
                }

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$subDir")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    if (contentLength > 0) {
                        put(MediaStore.MediaColumns.SIZE, contentLength)
                    }
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                }

                val uri: Uri? = try {
                    resolver.insert(collection, values)
                } catch (e: Exception) {
                    Log.e(TAG, "MediaStore.insert threw for $relative", e)
                    return false
                }
                if (uri == null) {
                    Log.e(TAG, "MediaStore.insert returned null for $relative (sub=$subDir, name=$displayName)")
                    return false
                }
                Log.i(TAG, "inserted uri=$uri for Download/$subDir/$displayName")

                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        body.byteStream().use { input ->
                            input.copyTo(out)
                            out.flush()
                        }
                    } ?: run {
                        resolver.delete(uri, null, null)
                        return false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "write body failed", e)
                    resolver.delete(uri, null, null)
                    return false
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }

                Log.i(TAG, "saved $displayName (${contentLength}B) to Downloads/$subDir")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "download failed for $url", e)
            false
        }
    }
}
