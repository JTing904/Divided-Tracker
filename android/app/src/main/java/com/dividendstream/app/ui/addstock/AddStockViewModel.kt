package com.dividendstream.app.ui.addstock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.remote.StockDetailDto
import com.dividendstream.app.data.remote.StockSummaryDto
import com.dividendstream.app.data.repository.PortfolioRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

data class AddStockUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<StockSummaryDto> = emptyList(),
    val selected: StockSummaryDto? = null,
    val selectedDetail: StockDetailDto? = null,
    val quantity: String = "",
    val averagePrice: String = "",
    val isSubmitting: Boolean = false,
    val error: AppError? = null,
    val savedHoldingSymbol: String? = null,
) {
    val parsedQuantity: BigDecimal? get() = quantity.toBigDecimalOrNullSafe()
    val parsedAveragePrice: BigDecimal? get() = averagePrice.toBigDecimalOrNullSafe()

    /**
     * Preview of what this position is expected to pay per cycle. Shown as an estimate; the
     * backend recomputes it authoritatively when the holding is saved.
     */
    val expectedDividend: BigDecimal?
        get() {
            val shares = parsedQuantity ?: return null
            val perShare = selectedDetail?.dividendPerShare ?: return null
            return shares.multiply(perShare).setScale(2, RoundingMode.HALF_UP)
        }

    val canSubmit: Boolean
        get() = selected != null &&
            (parsedQuantity?.signum() ?: 0) > 0 &&
            (parsedAveragePrice?.signum() ?: -1) >= 0 &&
            !isSubmitting
}

private fun String.toBigDecimalOrNullSafe(): BigDecimal? =
    trim().replace(",", "").takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

class AddStockViewModel(private val portfolioRepository: PortfolioRepository) : ViewModel() {

    private val _state = MutableStateFlow(AddStockUiState())
    val state = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Debounced: a search fires only once typing pauses, not on every keystroke. */
    fun onQueryChange(value: String) {
        _state.update { it.copy(query = value, error = null) }
        searchJob?.cancel()

        if (value.isBlank()) {
            _state.update { it.copy(results = emptyList(), isSearching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            _state.update { it.copy(isSearching = true) }
            when (val result = portfolioRepository.searchStocks(value)) {
                is AppResult.Success -> _state.update { it.copy(isSearching = false, results = result.data) }
                is AppResult.Failure -> _state.update { it.copy(isSearching = false, error = result.error) }
            }
        }
    }

    fun onSelect(stock: StockSummaryDto) {
        _state.update {
            it.copy(
                selected = stock,
                results = emptyList(),
                query = stock.companyName,
                averagePrice = it.averagePrice.ifBlank { stock.lastPrice?.toPlainString().orEmpty() },
            )
        }

        // Fetch the dividend details so the user sees what the position is expected to pay
        // before committing to it.
        viewModelScope.launch {
            when (val result = portfolioRepository.stockDetail(stock.symbol)) {
                is AppResult.Success -> _state.update { it.copy(selectedDetail = result.data) }
                is AppResult.Failure -> _state.update { it.copy(selectedDetail = null) }
            }
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = null, selectedDetail = null, query = "", results = emptyList()) }
    }

    fun onQuantityChange(value: String) = _state.update { it.copy(quantity = value, error = null) }

    fun onAveragePriceChange(value: String) = _state.update { it.copy(averagePrice = value, error = null) }

    fun submit() {
        val current = _state.value
        val stock = current.selected ?: return
        val quantity = current.parsedQuantity ?: return
        val averagePrice = current.parsedAveragePrice ?: return

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            when (val result = portfolioRepository.addHolding(stock.symbol, quantity, averagePrice)) {
                is AppResult.Success -> _state.update {
                    it.copy(isSubmitting = false, savedHoldingSymbol = result.data.symbol)
                }

                is AppResult.Failure -> _state.update { it.copy(isSubmitting = false, error = result.error) }
            }
        }
    }

    fun consumeSaved() = _state.update { it.copy(savedHoldingSymbol = null) }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
