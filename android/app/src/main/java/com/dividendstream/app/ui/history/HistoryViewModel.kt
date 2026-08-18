package com.dividendstream.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.remote.DividendDto
import com.dividendstream.app.data.remote.DividendHistoryDto
import com.dividendstream.app.data.repository.DividendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HistoryUiState(
    val isLoading: Boolean = true,
    /** A pull-to-refresh in flight. Distinct from [isLoading], which blanks the screen. */
    val isRefreshing: Boolean = false,
    val history: DividendHistoryDto? = null,
    /** The dividend whose real payment date is being asked for, if any. */
    val confirming: DividendDto? = null,
    val isConfirming: Boolean = false,
    val error: AppError? = null,
) {
    val isEmpty: Boolean get() = history != null && history.months.isEmpty()
}

class HistoryViewModel(private val dividendRepository: DividendRepository) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun startConfirming(dividend: DividendDto) = _state.update { it.copy(confirming = dividend) }

    fun cancelConfirming() = _state.update { it.copy(confirming = null) }

    /**
     * Reports the day a dividend actually arrived.
     *
     * Worth more than tidying one row: the backend infers this issuer's real payment lag from
     * these, so every later estimate for the stock stops being a guess. History is reloaded
     * because the settled date changes with it.
     */
    fun confirmReceived(receivedOn: LocalDate) {
        val dividend = _state.value.confirming ?: return
        viewModelScope.launch {
            _state.update { it.copy(isConfirming = true, error = null) }
            when (val result = dividendRepository.confirmReceived(dividend.id, receivedOn)) {
                is AppResult.Success -> {
                    _state.update { it.copy(isConfirming = false, confirming = null) }
                    refresh()
                }

                is AppResult.Failure -> _state.update {
                    it.copy(isConfirming = false, error = result.error)
                }
            }
        }
    }

    /** [fromPull] keeps the pull-to-refresh spinner turning over data already on screen. */
    fun refresh(fromPull: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(isLoading = it.history == null, isRefreshing = fromPull, error = null)
            }
            when (val result = dividendRepository.history()) {
                is AppResult.Success -> _state.update {
                    it.copy(isLoading = false, isRefreshing = false, history = result.data, error = null)
                }

                is AppResult.Failure -> _state.update {
                    it.copy(isLoading = false, isRefreshing = false, error = result.error)
                }
            }
        }
    }
}
