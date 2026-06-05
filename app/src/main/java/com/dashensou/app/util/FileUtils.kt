package com.dashensou.app.util

import java.io.File

/**
 * P1#19: the previous FileUtils was a 60-line kitchen-sink of
 * download-directory / extension / size helpers, almost all of
 * which were dead code (the only surviving caller is [deleteFile]).
 * Trimmed to the one function the app still uses; if a future
 * caller needs any of the removed helpers, reach for [FileTypes]
 * (extensions) or write the file in place where it's needed
 * (download directory).
 */
object FileUtils {

    /**
     * Best-effort delete of a file path. Returns true on success
     * or false if the file doesn't exist or the OS rejected the
     * delete (e.g. read-only, owned by another user). Never
     * throws.
     */
    fun deleteFile(filePath: String): Boolean {
        if (filePath.isBlank()) return false
        return try {
            val file = File(filePath)
            file.exists() && file.delete()
        } catch (e: SecurityException) {
            false
        }
    }
}
