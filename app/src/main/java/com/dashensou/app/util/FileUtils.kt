package com.dashensou.app.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.dashensou.app.data.model.ResourceCategory
import java.io.File

object FileUtils {

    fun getDownloadDirectory(context: Context, category: ResourceCategory): File {
        val baseDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        } else {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        }

        val categoryDir = when (category) {
            ResourceCategory.EBOOK -> "book"
            ResourceCategory.MOVIE -> "movie"
            ResourceCategory.TV -> "tv"
            else -> "other"
        }

        val dir = File(baseDir, "DaShenSou/$categoryDir")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getFileNameFromUrl(url: String): String {
        val decodedUrl = Uri.decode(url)
        return decodedUrl.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: "download_${System.currentTimeMillis()}"
    }

    fun getFileExtension(url: String): String {
        val decodedUrl = Uri.decode(url)
        val lastDot = decodedUrl.lastIndexOf('.')
        val lastSlash = decodedUrl.lastIndexOf('/')
        if (lastDot > lastSlash) {
            return decodedUrl.substring(lastDot)
        }
        return ".unknown"
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0)
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024))
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    fun deleteFile(filePath: String): Boolean {
        val file = File(filePath)
        return file.exists() && file.delete()
    }

    fun fileExists(filePath: String): Boolean {
        return File(filePath).exists()
    }
}
