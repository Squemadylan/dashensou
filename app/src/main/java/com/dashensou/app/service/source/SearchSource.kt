package com.dashensou.app.service.source

import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult

sealed class SearchOutcome {
    data class Success(val results: List<SearchResult>) : SearchOutcome()
    data class Failure(val message: String, val cause: Throwable? = null) : SearchOutcome()
}

interface SearchSource {
    val id: String
    val displayName: String
    var enabled: Boolean

    suspend fun search(
        keyword: String,
        page: Int,
        category: ResourceCategory
    ): SearchOutcome
}
