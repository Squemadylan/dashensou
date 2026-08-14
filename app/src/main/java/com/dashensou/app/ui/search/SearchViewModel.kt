package com.dashensou.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dashensou.app.App
import com.dashensou.app.data.model.ResourceCategory
import com.dashensou.app.data.model.SearchResult
import com.dashensou.app.service.SearchService
import com.dashensou.app.service.linkcheck.LinkChecker
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
class SearchViewModel : ViewModel() {
    val searchService: SearchService
        get() = App.searchService

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var inFlight: Job? = null
    private var linkCheckJob: Job? = null
    private var searchGeneration = 0

    data class SearchUiState(
        val keyword: String = "",
        val page: Int = 1,
        val category: ResourceCategory = ResourceCategory.ALL,
        val results: List<SearchResult> = emptyList(),
        val isLoading: Boolean = false,
        val failure: SearchOutcome.Failure? = null
    )

    fun search(keyword: String, page: Int = 1) {
        val cat = _state.value.category
        searchInternal(keyword, page, cat)
    }

    fun setCategory(category: ResourceCategory) {
        val kw = _state.value.keyword
        searchInternal(kw, 1, category)
    }

    fun refresh() {
        val s = _state.value
        searchInternal(s.keyword, s.page, s.category)
    }

    fun clear() {
        inFlight?.cancel()
        inFlight = null
        linkCheckJob?.cancel()
        linkCheckJob = null
        _state.value = SearchUiState()
    }

    fun clearFailure() {
        _state.update { it.copy(failure = null) }
    }

    private fun searchInternal(keyword: String, page: Int, category: ResourceCategory) {
        inFlight?.cancel()
        inFlight = null
        linkCheckJob?.cancel()
        linkCheckJob = null

        val cleaned = keyword.trim()
        val generation = ++searchGeneration

        if (cleaned.isEmpty()) {
            _state.value = SearchUiState(
                keyword = "",
                page = page,
                category = category,
                results = emptyList(),
                isLoading = false
            )
            return
        }

        _state.value = _state.value.copy(
            keyword = cleaned,
            page = page,
            category = category,
            isLoading = true,
            failure = null
        )

        inFlight = viewModelScope.launch {
            val outcome = try {
                searchService.search(cleaned, page, category)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                SearchOutcome.Failure.parse("搜索失败: ${t.message}", t)
            }

            if (generation != searchGeneration) return@launch

            when (outcome) {
                is SearchOutcome.Success -> {
                    val filtered = if (category == ResourceCategory.ALL) {
                        outcome.results
                    } else {
                        outcome.results.filter { CategoryRules.matches(it, category) }
                    }
                    _state.value = _state.value.copy(
                        results = filtered,
                        isLoading = false,
                        failure = null
                    )
                    startLinkChecks(generation, cleaned, filtered)
                }
                is SearchOutcome.Failure -> {
                    _state.value = _state.value.copy(
                        results = emptyList(),
                        isLoading = false,
                        failure = outcome
                    )
                }
            }
        }
    }

    private fun startLinkChecks(
        generation: Int,
        keyword: String,
        results: List<SearchResult>
    ) {
        linkCheckJob?.cancel()
        linkCheckJob = viewModelScope.launch {
            LinkChecker.checkResults(results) { updated ->
                if (generation != searchGeneration) return@checkResults
                _state.update { s ->
                    if (s.keyword != keyword) return@update s
                    val next = s.results.map { row ->
                        if (sameRow(row, updated)) updated else row
                    }
                    s.copy(results = next)
                }
            }
        }
    }

    private fun sameRow(a: SearchResult, b: SearchResult): Boolean {
        if (a.id.isNotBlank() && b.id.isNotBlank() && a.id == b.id) return true
        return a.sourceId == b.sourceId &&
            a.url.equals(b.url, ignoreCase = true)
    }
}
