package com.dashensou.app.database

import androidx.room.*
import com.dashensou.app.data.model.DownloadRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadRecordDao {
    @Query("SELECT * FROM download_records ORDER BY downloadTime DESC")
    fun getAllDownloadRecords(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM download_records WHERE status = :status ORDER BY downloadTime DESC")
    fun getDownloadRecordsByStatus(status: String): Flow<List<DownloadRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDownloadRecord(record: DownloadRecord): Long

    @Update
    suspend fun updateDownloadRecord(record: DownloadRecord)

    @Delete
    suspend fun deleteDownloadRecord(record: DownloadRecord)

    @Query("DELETE FROM download_records")
    suspend fun clearAllDownloadRecords()

    @Query("SELECT * FROM download_records WHERE id = :id LIMIT 1")
    suspend fun getDownloadRecordById(id: Long): DownloadRecord?
}
