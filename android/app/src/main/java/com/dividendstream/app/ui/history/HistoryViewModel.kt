package com.dividendstream.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.remote.DividendHistoryDto
import com.dividendstream.app.data.repository.DividendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HistoryUiState(
    val isLoading: Boolean = true,
    val history: DividendHistoryDto? = null,
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

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.history == null, error = null) }
            when (val result = dividendRepository.history()) {
                is AppResult.Success -> _state.update {
                    it.copy(isLoading = false, history = result.data, error = null)
                }

                is AppResult.Failure -> _state.update { it.copy(isLoading = false, error = result.error) }
            }
        }
    }
}
