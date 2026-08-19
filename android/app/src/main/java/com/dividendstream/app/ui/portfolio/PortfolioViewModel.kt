package com.dividendstream.app.ui.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.remote.PortfolioDto
import com.dividendstream.app.data.repository.PortfolioRepository
import com.dividendstream.app.data.repository.PurchaseQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class PortfolioUiState(
    val isLoading: Boolean = true,
    /** A pull-to-refresh in flight. Distinct from [isLoading], which blanks the screen. */
    val isRefreshing: Boolean = false,
    val portfolio: PortfolioDto? = null,
    val isStale: Boolean = false,
    val cachedAt: Instant? = null,
    /** Why the displayed copy is stale, once known. Null while the check is still running. */
    val staleError: AppError? = null,
    val error: AppError? = null,
    val actionError: AppError? = null,
) {
    val isEmpty: Boolean get() = portfolio != null && portfolio.holdings.isEmpty()
}

class PortfolioViewModel(
    private val portfolioRepository: PortfolioRepository,
    private val purchaseQueue: PurchaseQueue,
) : ViewModel() {

    private val _state = MutableStateFlow(PortfolioUiState())
    val state = _state.asStateFlow()

    /**
     * Purchases entered but not yet accepted, shown apart from the holdings rather than folded
     * into them. The figures on this screen are the ones the server has confirmed; a queued
     * purchase that turns out to be refused must never have moved them.
     */
    val pending = purchaseQueue.pending
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun discardPending(idempotencyKey: String) {
        viewModelScope.launch { purchaseQueue.discard(idempotencyKey) }
    }

    fun retryPending(idempotencyKey: String) {
        viewModelScope.launch { purchaseQueue.retry(idempotencyKey) }
    }

    init {
        refresh()
    }

    /** [fromPull] keeps the pull-to-refresh spinner turning over data already on screen. */
    fun refresh(fromPull: Boolean = false) {
        viewModelScope.launch {
            // Saved copy first: the server sleeps between uses, and holdings that were correct
            // a minute ago are worth more than a spinner. See DividendRepository.cachedLive.
            if (_state.value.portfolio == null) {
                portfolioRepository.cachedPortfolio()?.let { cached ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            portfolio = cached.value,
                            isStale = true,
                            cachedAt = cached.cachedAt,
                            staleError = null,
                        )
                    }
                }
            }

            _state.update {
                it.copy(isLoading = it.portfolio == null, isRefreshing = fromPull, error = null)
            }

            when (val result = portfolioRepository.portfolio()) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        portfolio = result.data.value,
                        isStale = result.data.isStale,
                        cachedAt = result.data.cachedAt,
                        staleError = result.data.staleError,
                        error = null,
                    )
                }

                is AppResult.Failure -> _state.update {
                    if (it.portfolio != null) {
                        it.copy(isLoading = false, isRefreshing = false, staleError = result.error)
                    } else {
                        it.copy(isLoading = false, isRefreshing = false, error = result.error)
                    }
                }
            }
        }
    }

    fun deleteHolding(id: String) {
        viewModelScope.launch {
            when (val result = portfolioRepository.deleteHolding(id)) {
                is AppResult.Success -> refresh()
                is AppResult.Failure -> _state.update { it.copy(actionError = result.error) }
            }
        }
    }

    fun dismissActionError() = _state.update { it.copy(actionError = null) }
}
