package com.dividendstream.app.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.data.remote.toAccumulationStream
import com.dividendstream.app.data.repository.LedgerRepository
import com.dividendstream.app.domain.AccumulationStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Which stretch of time the screen is showing.
 *
 * A view, not a second set of books. The month is where the running figures live; the day
 * narrows the same data to today. Overspending today shows as a negative day *and* stays in
 * the month, because midnight is not a reason for money that was spent to stop having been.
 */
enum class LedgerPeriod(val wire: String, val label: String) {
    Day("DAY", "Today"),
    Month("MONTH", "This month"),
}

/** How the records are ordered. */
enum class LedgerSort(val label: String) {
    Newest("Newest"),
    Oldest("Oldest"),
    Largest("Largest"),
}

/** How the funds are ordered. */
enum class FundSort(val label: String) {
    Biggest("Biggest"),
    Share("Share"),
    Name("Name"),
    Added("Added"),
}

/** Money in and money out, as two sets of parameters the counter ticks from. */
data class LedgerStreams(
    val income: List<AccumulationStream> = emptyList(),
    val expense: List<AccumulationStream> = emptyList(),
)

data class LedgerUiState(
    val isLoading: Boolean = true,
    /** A pull-to-refresh in flight. Distinct from [isLoading], which blanks the screen. */
    val isRefreshing: Boolean = false,
    val ledger: LedgerDto? = null,
    /** Derived once per refresh, not per frame. */
    val streams: LedgerStreams = LedgerStreams(),
    val isStale: Boolean = false,
    val cachedAt: Instant? = null,
    val staleError: AppError? = null,
    val error: AppError? = null,
    /** A save or delete that failed, shown over the screen rather than replacing it. */
    val actionError: AppError? = null,
    val isSaving: Boolean = false,
    val period: LedgerPeriod = LedgerPeriod.Month,
    val sort: LedgerSort = LedgerSort.Newest,
    val fundSort: FundSort = FundSort.Biggest,
    /**
     * The day whose records are listed, or null for the whole period.
     *
     * Nothing is deleted when a month rolls over -- a record is kept for good -- so the list
     * grows without limit and becomes the longest thing on the screen. Narrowing it to one day
     * and putting a calendar above it keeps the records reachable without them taking over.
     */
    val selectedDay: LocalDate? = null,
) {
    /** The funds, in the chosen order. Sorted here so the screen does no work per frame. */
    val sortedFunds: List<com.dividendstream.app.data.remote.FundDto>
        get() = ledger?.funds.orEmpty().let { funds ->
            when (fundSort) {
                FundSort.Biggest -> funds.sortedByDescending { it.balance }
                FundSort.Share -> funds.sortedByDescending { it.percent }
                FundSort.Name -> funds.sortedBy { it.name.lowercase() }
                FundSort.Added -> funds.sortedBy { it.position }
            }
        }

    /** The records for the chosen day, in the chosen order. Sorted here, not per frame. */
    val sortedEntries: List<com.dividendstream.app.data.remote.LedgerEntryDto>
        get() {
            val forDay = ledger?.entries.orEmpty()
                .filter { selectedDay == null || it.occurredOn == selectedDay }
            return when (sort) {
                LedgerSort.Newest -> forDay
                LedgerSort.Oldest -> forDay.sortedBy { it.occurredOn }
                LedgerSort.Largest -> forDay.sortedByDescending { it.amount }
            }
        }

    /** What each day of the period cost, for the calendar. Positive means money went out. */
    val spentByDay: Map<LocalDate, java.math.BigDecimal>
        get() = ledger?.entries.orEmpty()
            .groupBy { it.occurredOn }
            .mapValues { (_, rows) ->
                rows.fold(java.math.BigDecimal.ZERO) { sum, e ->
                    if (e.direction == "EXPENSE") sum.add(e.amount) else sum.subtract(e.amount)
                }
            }

    val hasNothing: Boolean
        get() = ledger != null && ledger.flows.isEmpty() && ledger.entries.isEmpty()
}

class LedgerViewModel(
    private val ledgerRepository: LedgerRepository,
    val serverClock: ServerClock,
) : ViewModel() {

    private val _state = MutableStateFlow(LedgerUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
    }

    /** Switching period reloads: the server measures the figures, not the screen. */
    fun setPeriod(period: LedgerPeriod) {
        if (_state.value.period == period) return
        _state.update { it.copy(period = period) }
        refresh()
    }

    fun setSort(sort: LedgerSort) = _state.update { it.copy(sort = sort) }

    fun setFundSort(sort: FundSort) = _state.update { it.copy(fundSort = sort) }

    /** Tapping the selected day again clears it, which is how a filter should behave. */
    fun selectDay(day: LocalDate?) = _state.update {
        it.copy(selectedDay = if (it.selectedDay == day) null else day)
    }

    fun refresh(fromPull: Boolean = false) {
        viewModelScope.launch {
            // Saved copy first, before the request goes out rather than after it fails. The
            // server sleeps between uses, and the figures here are derived from timestamps --
            // so a saved ledger is still counting, and still correct, while it wakes.
            if (_state.value.ledger == null) {
                ledgerRepository.cachedLedger()?.let { cached ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            ledger = cached.value,
                            streams = cached.value.toStreams(),
                            isStale = true,
                            cachedAt = cached.cachedAt,
                            staleError = null,
                        )
                    }
                }
            }

            _state.update {
                it.copy(isLoading = it.ledger == null, isRefreshing = fromPull, error = null)
            }

            when (val result = ledgerRepository.ledger(_state.value.period.wire)) {
                is AppResult.Success -> _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        ledger = result.data.value,
                        streams = result.data.value.toStreams(),
                        isStale = result.data.isStale,
                        cachedAt = result.data.cachedAt,
                        staleError = result.data.staleError,
                        error = null,
                    )
                }

                is AppResult.Failure -> _state.update {
                    if (it.ledger != null) {
                        it.copy(isLoading = false, isRefreshing = false, staleError = result.error)
                    } else {
                        it.copy(isLoading = false, isRefreshing = false, error = result.error)
                    }
                }
            }
        }
    }

    /**
     * [id] is generated once per save and reused on a retry, so a lost reply cannot record the
     * same thing twice. A save that succeeds reloads the ledger, because every figure on the
     * screen -- the rate, the funds, the month's totals -- moves when one flow changes.
     */
    fun saveFlow(
        id: String? = null,
        name: String,
        direction: String,
        amount: BigDecimal,
        period: String,
        category: String?,
        startsOn: LocalDate? = null,
        endsOn: LocalDate? = null,
        onSaved: () -> Unit = {},
    ) = perform(onSaved) {
        ledgerRepository.saveCashFlow(
            id = id ?: UUID.randomUUID().toString(),
            name = name,
            direction = direction,
            amount = amount,
            period = period,
            category = category,
            startsOn = startsOn,
            endsOn = endsOn,
        )
    }

    fun deleteFlow(id: String) = perform { ledgerRepository.deleteCashFlow(id) }

    fun saveEntry(
        id: String? = null,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate? = null,
        category: String?,
        note: String?,
        onSaved: () -> Unit = {},
    ) = perform(onSaved) {
        ledgerRepository.saveEntry(
            id = id ?: UUID.randomUUID().toString(),
            direction = direction,
            amount = amount,
            occurredOn = occurredOn,
            category = category,
            note = note,
        )
    }

    fun deleteEntry(id: String) = perform { ledgerRepository.deleteEntry(id) }

    fun saveFund(
        id: String? = null,
        name: String,
        percent: BigDecimal,
        icon: String?,
        onSaved: () -> Unit = {},
    ) = perform(onSaved) {
        ledgerRepository.saveFund(
            id = id ?: UUID.randomUUID().toString(),
            name = name,
            percent = percent,
            icon = icon,
        )
    }

    fun deleteFund(id: String) = perform { ledgerRepository.deleteFund(id) }

    /**
     * Records money going into or out of a fund.
     *
     * Never called on the person's behalf. The plan saying RM412 should reach the emergency
     * fund is not the same as RM412 being in it, and only they know which happened.
     */
    fun moveFundMoney(
        fundId: String,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate? = null,
        note: String? = null,
        onSaved: () -> Unit = {},
    ) = perform(onSaved) {
        ledgerRepository.saveFundMovement(
            fundId = fundId,
            id = UUID.randomUUID().toString(),
            direction = direction,
            amount = amount,
            occurredOn = occurredOn,
            note = note,
        )
    }

    fun deleteFundMovement(id: String) = perform { ledgerRepository.deleteFundMovement(id) }

    fun dismissActionError() = _state.update { it.copy(actionError = null) }

    private fun perform(onSuccess: () -> Unit = {}, block: suspend () -> AppResult<*>) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, actionError = null) }
            when (val result = block()) {
                is AppResult.Success -> {
                    _state.update { it.copy(isSaving = false) }
                    onSuccess()
                    refresh()
                }

                is AppResult.Failure ->
                    _state.update { it.copy(isSaving = false, actionError = result.error) }
            }
        }
    }
}

/**
 * Splits the declared flows into the two sets the counter needs.
 *
 * A flow not live in the current month yields no stream at all, rather than a stream worth
 * zero: it has nothing to contribute, and the calculator would otherwise be handed a window
 * it cannot make sense of.
 */
private fun LedgerDto.toStreams(): LedgerStreams = LedgerStreams(
    income = flows.filter { it.direction == "INCOME" }.mapNotNull { it.toAccumulationStream() },
    expense = flows.filter { it.direction == "EXPENSE" }.mapNotNull { it.toAccumulationStream() },
)
