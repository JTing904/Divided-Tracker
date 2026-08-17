package com.dividendstream.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.core.dataOrNull
import com.dividendstream.app.data.remote.HoldingDto
import com.dividendstream.app.data.remote.LiveStreamDto
import com.dividendstream.app.data.remote.StockDetailDto
import com.dividendstream.app.data.remote.toAccumulationStream
import com.dividendstream.app.data.repository.DividendRepository
import com.dividendstream.app.data.repository.PortfolioRepository
import com.dividendstream.app.domain.AccumulationStream
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HoldingDetailUiState(
    val isLoading: Boolean = true,
    val stock: StockDetailDto? = null,
    val holding: HoldingDto? = null,
    val liveStreams: List<LiveStreamDto> = emptyList(),
    val accumulationStreams: List<AccumulationStream> = emptyList(),
    val error: AppError? = null,
)

class HoldingDetailViewModel(
    private val symbol: String,
    private val portfolioRepository: PortfolioRepository,
    private val dividendRepository: DividendRepository,
    val serverClock: ServerClock,
) : ViewModel() {

    private val _state = MutableStateFlow(HoldingDetailUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = it.stock == null, error = null) }

            // Three independent reads; run them concurrently rather than in sequence.
            val stockDeferred = async { portfolioRepository.stockDetail(symbol) }
            val portfolioDeferred = async { portfolioRepository.portfolio() }
            val liveDeferred = async { dividendRepository.live() }

            val stockResult = stockDeferred.await()
            val portfolio = portfolioDeferred.await().dataOrNull()?.value
            val live = liveDeferred.await().dataOrNull()?.value

            if (stockResult is AppResult.Failure) {
                _state.update { it.copy(isLoading = false, error = stockResult.error) }
                return@launch
            }

            val stock = (stockResult as AppResult.Success).data
            val holding = portfolio?.holdings?.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }
            val streams = live?.streams.orEmpty().filter { it.symbol.equals(symbol, ignoreCase = true) }

            _state.update {
                it.copy(
                    isLoading = false,
                    stock = stock,
                    holding = holding,
                    liveStreams = streams,
                    accumulationStreams = streams.map { stream -> stream.toAccumulationStream() },
                    error = null,
                )
            }
        }
    }
}
