package com.dividendstream.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.remote.DividendDto
import com.dividendstream.app.data.repository.DividendRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.YearMonth
import java.time.ZoneOffset

/** Upcoming payments for one calendar month. */
data class CalendarMonth(
    val yearMonth: YearMonth,
    val total: BigDecimal,
    val items: List<DividendDto>,
)

data class CalendarUiState(
    val isLoading: Boolean = true,
    /** A pull-to-refresh in flight. Distinct from [isLoading], which blanks the screen. */
    val isRefreshing: Boolean = false,
    val currency: String = "MYR",
    val totalExpected: BigDecimal = BigDecimal.ZERO,
    val expectedThisMonth: BigDecimal = BigDecimal.ZERO,
    val eventCount: Int = 0,
    val months: List<CalendarMonth> = emptyList(),
    val error: AppError? = null,
) {
    val isEmpty: Boolean get() = !isLoading && months.isEmpty() && error == null
}

class CalendarViewModel(
    private val dividendRepository: DividendRepository,
    private val serverClock: ServerClock,
) : ViewModel() {

    private val _state = MutableStateFlow(CalendarUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    /** [fromPull] keeps the pull-to-refresh spinner turning over data already on screen. */
    fun refresh(fromPull: Boolean = false) {
        viewModelScope.launch {
            _state.update {
                it.copy(isLoading = it.months.isEmpty(), isRefreshing = fromPull, error = null)
            }

            when (val result = dividendRepository.upcoming()) {
                is AppResult.Success -> {
                    // atZone().toLocalDate() rather than LocalDate.ofInstant, which is a
                    // Java 9 API and absent on API 26.
                    val today = serverClock.now().atZone(ZoneOffset.UTC).toLocalDate()
                    val thisMonth = YearMonth.from(today)

                    val months = result.data.items
                        .groupBy { YearMonth.from(it.paymentDate) }
                        .toSortedMap()
                        .map { (month, items) ->
                            CalendarMonth(
                                yearMonth = month,
                                total = items.fold(BigDecimal.ZERO) { sum, item -> sum + item.expectedAmount },
                                items = items.sortedBy { it.paymentDate },
                            )
                        }

                    _state.update {
                        it.copy(
                            isLoading = false,
                            isRefreshing = false,
                            currency = result.data.currency,
                            totalExpected = result.data.totalExpected,
                            expectedThisMonth = months
                                .firstOrNull { month -> month.yearMonth == thisMonth }
                                ?.total ?: BigDecimal.ZERO,
                            eventCount = result.data.items.size,
                            months = months,
                            error = null,
                        )
                    }
                }

                is AppResult.Failure -> _state.update {
                    it.copy(isLoading = false, isRefreshing = false, error = result.error)
                }
            }
        }
    }
}
