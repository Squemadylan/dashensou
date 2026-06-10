package com.dashensou.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.service.SearchService
import com.dashensou.app.service.source.SearchOutcome
import com.dashensou.app.util.CategoryRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the search-input state and the latest in-flight search result.
 */
class SearchViewModel(
    val searchService: SearchService = SearchService()
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var inFlight: Job? = null
    private var searchGeneration = 0

    fun setKeyword(keyword: String) {
        _state.update { it.copy(keyword = keyword) }
    }

    fun setCategory(category: ResourceCategory) {
        _state.update { it.copy(category = category) }
    }

    fun refresh() = search(
        keyword = _state.value.keyword,
        page = 1,
        category = _state.value.category
    )

    fun clear() {
        inFlight?.cancel()
        inFlight = null
        _state.update {
            SearchUiState(category = it.category)
        }
    }

    fun search(
        keyword: String,
        page: Int = 1,
        category: ResourceCategory = _state.value.category
    ) {
        if (keyword.isBlank()) {
            clear()
            return
        }
        inFlight?.cancel()
        val generation = ++searchGeneration
        _state.update {
            it.copy(keyword = keyword, page = page, category = category, loading = true, failure = null)
        }
        inFlight = viewModelScope.launch {
            try {
                val outcome = searchService.search(keyword, page, category)
                if (generation != searchGeneration) return@launch
                when (outcome) {
                    is SearchOutcome.Success -> {
                        _state.update {
                            if (generation != searchGeneration) return@update it
                            it.copy(
                                loading = false,
                                results = outcome.results,
                                failure = null
                            )
                        }
                    }
                    is SearchOutcome.Failure -> {
                        _state.update {
                            if (generation != searchGeneration) return@update it
                            it.copy(loading = false, failure = outcome)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != searchGeneration) return@launch
                _state.update {
                    it.copy(
                        loading = false,
                        failure = SearchOutcome.Failure(
                            message = "搜索异常: ${e.message}",
                            kind = com.dashensou.app.service.source.FailureKind.UNKNOWN,
                            cause = e
                        )
                    )
                }
            }
        }
    }

    fun clearFailure() {
        _state.update { it.copy(failure = null) }
    }
}

data class SearchUiState(
    val keyword: String = "",
    val category: ResourceCategory = ResourceCategory.ALL,
    val page: Int = 1,
    val loading: Boolean = false,
    val results: List<SearchResult> = emptyList(),
    val failure: SearchOutcome.Failure? = null
) {
    val visibleResults: List<SearchResult>
        get() = if (category == ResourceCategory.ALL) {
            results
        } else {
            results.filter { CategoryRules.matches(it, category) }
        }
}
