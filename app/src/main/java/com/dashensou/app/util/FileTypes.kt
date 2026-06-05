package com.dashensou.app.util

import com.dashensou.app.data.model.NetDiskType

/**
 * Centralised registry of file extensions / display labels used by
 * every search source. Previously every source carried its own
 * hardcoded `listOf("pdf", "epub", ...)` block, and the renderer /
 * DownloadManager carried yet another copy. When we needed to add
 * "azw3" we had to find and patch 4 files; this collapses them to
 * one.
 */
object FileTypes {

    /** Extensions that identify an ebook when seen in a URL or a title. */
    val EBOOK: Set<String> = setOf(
        "epub", "mobi", "azw3", "pdf", "txt", "zip", "html", "htm"
    )

    /** Extensions that identify a video file. */
    val VIDEO: Set<String> = setOf(
        "mp4", "mkv", "avi", "rmvb", "ts", "mov", "flv", "webm"
    )

    /** Extensions that identify an audio file. */
    val AUDIO: Set<String> = setOf("mp3", "m4a", "flac", "ogg", "wav")

    /** Extensions that identify a generic archive. */
    val ARCHIVE: Set<String> = setOf("zip", "rar", "7z", "tar", "gz", "iso")

    /** The full allow-list used by DownloadManager.getFileName() to
     *  pick a real extension off a URL path. Anything not in this set
     *  is treated as "no extension known" and the file is written as
     *  .download so the user can rename later. */
    val KNOWN: Set<String> = EBOOK + VIDEO + AUDIO + ARCHIVE

    /**
     * Map the lowercase extension (no dot) to a normalised fileType
     * token used as the SearchResult.fileType. Null when we can't
     * tell.
     */
    fun normalise(ext: String?): String? {
        if (ext.isNullOrBlank()) return null
        val e = ext.lowercase()
        return when {
            e in EBOOK -> if (e == "zip" || e == "rar" || e == "7z" || e == "iso" || e == "tar" || e == "gz") "archive" else e
            e in VIDEO -> "video"
            e in AUDIO -> "audio"
            e == "magnet" -> "magnet"
            else -> null
        }
    }

    /**
     * Detect file type from a title string by looking at its suffix
     * after the last dot. Returns the canonical token or null. This
     * is the single source of truth for "title ends in .pdf" ->
     * "this is a pdf"; every source used to inline this check.
     */
    fun detectFromTitle(title: String): String? {
        val lower = title.lowercase()
        // Order matters: archive (zip/rar/7z) must be checked before
        // generic extension lookup so ".zip" doesn't get
        // mis-classified as the "zip" token.
        val direct = listOf(
            ".pdf" to "pdf",
            ".epub" to "epub",
            ".azw3" to "mobi",
            ".mobi" to "mobi",
            ".txt" to "txt",
            ".html" to "html", ".htm" to "html",
            ".mp4" to "video", ".mkv" to "video", ".avi" to "video",
            ".rmvb" to "video", ".ts" to "video", ".mov" to "video", ".flv" to "video",
            ".mp3" to "audio", ".m4a" to "audio",
            ".zip" to "archive", ".rar" to "archive", ".7z" to "archive"
        )
        for ((needle, token) in direct) {
            if (lower.contains(needle)) return token
        }
        return null
    }

    /**
     * Extract a normalised extension from a URL *path* (the query
     * string and fragment are ignored on purpose — a CDN URL with
     * `?type=zipbook` in its query must not be mis-classified).
     */
    fun detectFromUrl(url: String): String? {
        val pathOnly = url.substringBefore('?').substringBefore('#')
        val ext = pathOnly.substringAfterLast('/', "")
            .substringAfterLast('.', "")
        return normalise(ext)
    }
}

/**
 * Single place that knows how to label a NetDiskType in the UI.
 * Replaces the two near-identical `diskLabel()` functions that
 * lived in MainActivity and SearchResultAdapter.
 */
object DiskLabels {
    fun short(type: NetDiskType): String = when (type) {
        NetDiskType.QUARK -> "夸克"
        NetDiskType.BAIDU -> "度盘"
        NetDiskType.XUNLEI -> "迅雷"
        NetDiskType.ALIYUN -> "阿里"
        NetDiskType.YUNPAN123 -> "123盘"
        NetDiskType.DIRECT_URL -> "直链"
        NetDiskType.OTHER -> "网盘"
    }

    fun long(type: NetDiskType): String = when (type) {
        NetDiskType.QUARK -> "夸克网盘"
        NetDiskType.BAIDU -> "百度网盘"
        NetDiskType.XUNLEI -> "迅雷网盘"
        NetDiskType.ALIYUN -> "阿里云盘"
        NetDiskType.YUNPAN123 -> "123云盘"
        NetDiskType.DIRECT_URL -> "直接下载"
        NetDiskType.OTHER -> "其他"
    }
}
