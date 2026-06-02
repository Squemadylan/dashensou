package com.dashensou.app.database

import androidx.room.*
import com.dashensou.app.data.model.SearchHistory
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchTime DESC")
    fun getAllSearchHistory(): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_history ORDER BY searchTime DESC")
    fun getAllHistory(): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_history ORDER BY searchCount DESC, searchTime DESC LIMIT :limit")
    fun getHotSearchHistory(limit: Int): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_history WHERE keyword LIKE '%' || :keyword || '%' ORDER BY searchTime DESC")
    fun searchHistoryByKeyword(keyword: String): Flow<List<SearchHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistory(history: SearchHistory)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: SearchHistory)

    @Query("SELECT * FROM search_history WHERE keyword = :keyword LIMIT 1")
    suspend fun getSearchHistoryByKeyword(keyword: String): SearchHistory?

    @Query("SELECT * FROM search_history WHERE keyword = :keyword LIMIT 1")
    suspend fun getHistoryByKeyword(keyword: String): SearchHistory?

    @Update
    suspend fun updateSearchHistory(history: SearchHistory)

    @Update
    suspend fun updateHistory(history: SearchHistory)

    @Delete
    suspend fun deleteSearchHistory(history: SearchHistory)

    @Query("DELETE FROM search_history")
    suspend fun clearAllSearchHistory()
}
