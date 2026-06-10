package com.dashensou.app.util

import android.os.Environment

/**
 * Single source of truth for MediaStore Download relative paths.
 * Writer ([com.dashensou.app.service.DirectDownloader]) and reader
 * ([FileOpener]) must use the same normalization so queries hit.
 */
object MediaStorePaths {

    /** e.g. "Download/Book/" — always ends with '/'. */
    fun relativePath(subDir: String): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$subDir/"

    /** Stored in Room / shown to user: "Download/Book/file.txt". */
    fun recordPath(subDir: String, displayName: String): String =
        "${relativePath(subDir).trimEnd('/')}/$displayName"

    /**
     * Parse a record path "Download/Book/file.txt" into
     * (relativePath, displayName) for MediaStore query.
     */
    fun parseRecordPath(filePath: String): Pair<String, String>? {
        if (filePath.startsWith("/")) return null
        val parts = filePath.split("/", limit = 3)
        if (parts.size != 3) return null
        return relativePath(parts[1]) to parts[2]
    }
}
