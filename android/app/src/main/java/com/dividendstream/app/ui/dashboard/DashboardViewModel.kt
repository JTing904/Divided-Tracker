package com.dividendstream.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.remote.LiveDividendDto
import com.dividendstream.app.data.remote.toAccumulationStream
import com.dividendstream.app.data.repository.DividendRepository
import com.dividendstream.app.domain.AccumulationStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

data class DashboardUiState(
    val isLoading: Boolean = true,
    /** A pull-to-refresh in flight. Distinct from [isLoading], which blanks the screen. */
    val isRefreshing: Boolean = false,
    val snapshot: LiveDividendDto? = null,
    /** Parameters the counter ticks from. Derived once per refresh, not per frame. */
    val streams: List<AccumulationStream> = emptyList(),
    val isStale: Boolean = false,
    val cachedAt: Instant? = null,
    val error: AppError? = null,
) {
    val isEmpty: Boolean get() = snapshot != null && snapshot.streams.isEmpty()
}

class DashboardViewModel(
    private val dividendRepository: DividendRepository,
    val serverClock: ServerClock,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    /**
     * Pulls a fresh snapshot. Cheap on the backend -- a single read, no writes -- because the
     * accumulating value is derived rather than stored, so calling this on every resume is
     * fine.
     *
     * [fromPull] drives the pull-to-refresh spinner, which has to keep turning while data is
     * already on screen — the reason it cannot simply reuse [DashboardUiState.isLoading].
     */
    fun refresh(fromPull: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(isLoading = it.snapshot == null, isRefreshing = fromPull, error = null)
            }

            when (val result = dividendRepository.live()) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        snapshot = result.data.value,
                        streams = result.data.value.streams.map { stream -> stream.toAccumulationStream() },
                        isStale = result.data.isStale,
                        cachedAt = result.data.cachedAt,
                        error = null,
                    )
                }

                is AppResult.Failure -> _state.update {
                    it.copy(isLoading = false, isRefreshing = false, error = result.error)
                }
            }
        }
    }
}
