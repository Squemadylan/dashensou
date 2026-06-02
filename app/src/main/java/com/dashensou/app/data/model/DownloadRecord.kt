package com.dashensou.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED
}

@Entity(tableName = "download_records")
data class DownloadRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val url: String = "",
    val filePath: String = "",
    val fileSize: Long = 0,
    val downloadSize: Long = 0,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val downloadTime: Long = System.currentTimeMillis(),
    val netDiskType: NetDiskType = NetDiskType.OTHER,
    val category: ResourceCategory = ResourceCategory.ALL
)
