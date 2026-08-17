package com.dividendstream.app.ui.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.remote.PortfolioDto
import com.dividendstream.app.data.repository.PortfolioRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class PortfolioUiState(
    val isLoading: Boolean = true,
    val portfolio: PortfolioDto? = null,
    val isStale: Boolean = false,
    val cachedAt: Instant? = null,
    val error: AppError? = null,
    val actionError: AppError? = null,
) {
    val isEmpty: Boolean get() = portfolio != null && portfolio.holdings.isEmpty()
}

class PortfolioViewModel(private val portfolioRepository: PortfolioRepository) : ViewModel() {

    private val _state = MutableStateFlow(PortfolioUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.portfolio == null, error = null) }

            when (val result = portfolioRepository.portfolio()) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isLoading = false,
                        portfolio = result.data.value,
                        isStale = result.data.isStale,
                        cachedAt = result.data.cachedAt,
                        error = null,
                    )
                }

                is AppResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error) }
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
