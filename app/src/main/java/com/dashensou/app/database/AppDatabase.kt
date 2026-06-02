package com.dashensou.app.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.dashensou.app.data.model.DownloadRecord
import com.dashensou.app.data.model.SearchHistory

@Database(
    entities = [SearchHistory::class, DownloadRecord::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun downloadRecordDao(): DownloadRecordDao
}
