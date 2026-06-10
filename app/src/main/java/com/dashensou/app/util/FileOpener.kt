package com.dashensou.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Open a downloaded file with the system-default app via ACTION_VIEW.
 *
 * filePath can take several shapes:
 *  - Public path like "Download/Book/xxx.txt" (aiqu direct downloads written
 *    through MediaStore). Looked up by relative_path + display_name.
 *  - Public fallback path like "Download/DaShenSou_book_xxx.zip" (when the
 *    app-private write failed). Looked up the same way.
 *  - App-private absolute path like
 *    "/sdcard/Android/data/com.dashensou.app/files/Download/DaShenSou/...".
 *    Wrapped in a FileProvider URI since Android N+ blocks raw file://.
 *
 * Returns true on success, false otherwise (no handler, file missing, etc.).
 */
object FileOpener {
    private const val TAG = "FileOpener"

    fun open(context: Context, filePath: String): Boolean {
        Log.i(TAG, "open: filePath=$filePath")
        val uri = resolveUri(context, filePath) ?: run {
            Log.w(TAG, "resolveUri returned null for: $filePath")
            Toast.makeText(context, "文件不存在: $filePath", Toast.LENGTH_SHORT).show()
            return false
        }
        Log.i(TAG, "open: resolved uri=$uri")

        val mime = guessMimeType(filePath)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no handler for $mime ($filePath)")
            Toast.makeText(context, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "open denied for $filePath", e)
            Toast.makeText(context, "无法打开: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        } catch (e: Exception) {
            Log.e(TAG, "open failed for $filePath", e)
            Toast.makeText(context, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Public version of [resolveUri] so callers (e.g. the download
     * "delete record" action) can obtain the MediaStore / FileProvider
     * URI for a recorded filePath and clear it via
     * [android.content.ContentResolver.delete] -- just deleting the row
     * in Room left the actual file on disk and a stale MediaStore entry
     * (P0#3 fix).
     */
    fun resolveUri(context: Context, filePath: String): Uri? = resolveUriInternal(context, filePath)

    private fun resolveUriInternal(context: Context, filePath: String): Uri? {
        // Case 1: public MediaStore path. Look it up by relative_path + display_name.
        //
        // filePath looks like "Download/Book/凡人修仙.txt" — three slash-
        // separated segments: <root>/<subDir>/<displayName>. Earlier we used
        // `split("/", limit=2)` which collapsed everything after the first
        // slash into parts[1] and made the MediaStore lookup fail with
        // "file does not exist" on every download. Limit to 3 instead.
        if (!filePath.startsWith("/")) {
            val parsed = MediaStorePaths.parseRecordPath(filePath)
            if (parsed != null) {
                val (relativePath, displayName) = parsed
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Files.getContentUri("external")
                }
                val uri = findInMediaStore(context, collection, relativePath, displayName)
                if (uri != null) return uri
                // Fall through in case the file is actually present on legacy
                // external storage as a raw file (older Android versions).
            }
        }

        // Case 1b: legacy absolute filePath recorded by the older
        // system-DownloadManager path (e.g.
        //   /sdcard/Android/data/com.dashensou.app/files/Download/DaShenSou/book/xxx.txt
        // or, after the system-DM fallback, the public form
        //   /sdcard/Download/DaShenSou_book_xxx.zip
        // ). We can't query MediaStore by full path, but we can fall back to
        // "the file the user is most likely looking at" — its basename — and
        // search MediaStore by display_name. This rescues old download
        // records whose on-disk path no longer matches the path stored in
        // the database (the public path was written by Android's DM after
        // the scoped-storage fallback, the private path by the old code).
        val basename = File(filePath).name
        if (basename.isNotBlank()) {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Files.getContentUri("external")
            }
            val uri = findInMediaStoreByName(context, collection, basename)
            if (uri != null) return uri
        }

        // Case 2: app-private absolute path under external files dir.
        // Map back to a real File and wrap it in a FileProvider URI.
        val file = File(filePath)
        if (file.exists()) {
            return try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } catch (e: IllegalArgumentException) {
                // File lives outside any path declared in file_paths.xml.
                Log.e(TAG, "file outside FileProvider scope: $filePath", e)
                null
            }
        }

        // Case 3: legacy /sdcard/Download/<file> fallback path.
        val legacyFile = File(Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ), File(filePath).name)
        if (legacyFile.exists()) {
            return try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    legacyFile
                )
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "legacy file outside FileProvider scope", e)
                null
            }
        }

        return null
    }

    private fun findInMediaStore(
        context: Context,
        collection: Uri,
        relativePath: String,
        displayName: String
    ): Uri? {
        return try {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            val selectionArgs = arrayOf(relativePath, displayName)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        Uri.withAppendedPath(collection, id.toString())
                    } else null
                }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore query failed for $relativePath/$displayName", e)
            null
        }
    }

    /**
     * Fallback MediaStore lookup used by Case 1b: search by display_name
     * only, returning the most recently added match. Less precise than
     * findInMediaStore (two records with the same filename in different
     * subdirs will collide) but it's the only way to recover legacy
     * records whose recorded path no longer matches the actual on-disk
     * location.
     */
    private fun findInMediaStoreByName(
        context: Context,
        collection: Uri,
        displayName: String
    ): Uri? {
        return try {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=?"
            val selectionArgs = arrayOf(displayName)
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        Log.i(TAG, "findInMediaStoreByName: hit $displayName -> id=$id")
                        Uri.withAppendedPath(collection, id.toString())
                    } else null
                }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore name-only query failed for $displayName", e)
            null
        }
    }

    private fun guessMimeType(filePath: String): String {
        val mime = FileTypes.mimeTypeForFileName(filePath)
        return if (mime == "application/octet-stream") "*/*" else mime
    }
}
