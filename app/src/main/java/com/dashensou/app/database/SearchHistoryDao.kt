package com.dashensou.app.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.dashensou.app.data.model.SearchHistory
import kotlinx.coroutines.flow.Flow

/**
 * P1#19: the old version of this file carried two near-identical
 * queries — getAllSearchHistory / getAllHistory, insertSearchHistory /
 * insertHistory, getSearchHistoryByKeyword / getHistoryByKeyword,
 * updateSearchHistory / updateHistory. Both names pointed at the
 * same SQL; the duplicates only existed because two earlier code
 * paths each invented their own convention. Collapsed to one.
 */
@Dao
interface SearchHistoryDao {
    @Query("SELECT * FROM search_history ORDER BY searchTime DESC")
    fun getAllSearchHistory(): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_history ORDER BY searchCount DESC, searchTime DESC LIMIT :limit")
    fun getHotSearchHistory(limit: Int): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_history WHERE keyword LIKE '%' || :keyword || '%' ORDER BY searchTime DESC")
    fun searchHistoryByKeyword(keyword: String): Flow<List<SearchHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchHistory(history: SearchHistory)

    @Query("SELECT * FROM search_history WHERE keyword = :keyword LIMIT 1")
    suspend fun getSearchHistoryByKeyword(keyword: String): SearchHistory?

    @Update
    suspend fun updateSearchHistory(history: SearchHistory)

    @Query("DELETE FROM search_history")
    suspend fun clearAllSearchHistory()

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun deleteSearchHistory(id: Long)

    /** Convenience overload that resolves the row's id for callers
     *  holding the entity rather than a numeric id. */
    suspend fun deleteSearchHistory(history: SearchHistory) =
        deleteSearchHistory(history.id)
}
