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
 *
 * P1#7: the previous version of this logic lived inline in MainActivity
 * alongside 400+ lines of tab switching, dialogs and adapter wiring, so
 * any change to the search flow required touching the whole Activity.
 * This ViewModel just exposes a [SearchUiState] StateFlow and a few
 * intent-shaped methods; the Activity subscribes and renders.
 */
class SearchViewModel(
    val searchService: SearchService = SearchService()
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    // P0#robustness: keep a reference to the latest search coroutine so a
    // rapid re-keyword tap can cancel the in-flight request. Without this
    // an in-flight source that finishes AFTER the user has typed a new
    // keyword would race-override the newer results.
    private var inFlight: Job? = null

    fun setKeyword(keyword: String) {
        _state.update { it.copy(keyword = keyword) }
    }

    fun setCategory(category: ResourceCategory) {
        // P1#10: switching the category on the front-end filter never
        // triggers a network call. The results list is re-filtered in
        // place; the network fires only when the user actually edits the
        // keyword or pull-to-refreshes.
        _state.update { it.copy(category = category) }
    }

    fun refresh() = search(keyword = _state.value.keyword, page = 1)

    /**
     * Reset back to the empty / recommendation state (no keyword, no
     * results). Called when the user clears the search input and the
     * "loading recommendations" placeholder is shown.
     */
    fun clear() {
        inFlight?.cancel()
        inFlight = null
        _state.update {
            SearchUiState(category = it.category)
        }
    }

    fun search(keyword: String, page: Int = 1) {
        if (keyword.isBlank()) {
            clear()
            return
        }
        // Cancel any previous search coroutine so the user can never see a
        // late-arriving older result overwrite a newer one. The previous
        // job's HTTP requests are best-effort cancelled; the OkHttp call
        // may still complete on the IO dispatcher but its result is
        // discarded by the cancellation check below.
        inFlight?.cancel()
        _state.update { it.copy(keyword = keyword, page = page, loading = true, failure = null) }
        inFlight = viewModelScope.launch {
            try {
                val outcome = searchService.search(keyword, page, _state.value.category)
                when (outcome) {
                    is SearchOutcome.Success -> {
                        _state.update {
                            it.copy(
                                loading = false,
                                results = outcome.results,
                                failure = null
                            )
                        }
                    }
                    is SearchOutcome.Failure -> {
                        // Keep the prior results visible so the user can
                        // still see the last successful list while the
                        // dialog explains what went wrong.
                        _state.update {
                            it.copy(loading = false, failure = outcome)
                        }
                    }
                }
            } catch (e: CancellationException) {
                // Cancelled by a newer search or viewModelScope. Don't
                // touch the state — the next launch will overwrite it.
                throw e
            } catch (e: Exception) {
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

/**
 * Pure-data UI state. Filtered for the active category lives in
 * [visibleResults] (a derived property) so the ViewModel itself doesn't
 * have to expose two parallel lists; consumers can call
 * [SearchUiState.visibleResults] to get the right slice for the screen.
 *
 * The actual matching rules live in [CategoryRules] so the front-end
 * tab filter, the per-source `matchesCategory()` and any future
 * "is this a net-disk?" query share one definition.
 */
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
