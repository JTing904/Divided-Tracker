package com.dividendstream.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividendstream.app.core.AppError
import com.dividendstream.app.core.AppResult
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.data.remote.PortfolioDto
import com.dividendstream.app.data.remote.LiveDividendDto
import com.dividendstream.app.data.remote.toAccumulationStream
import com.dividendstream.app.data.repository.AppInfoRepository
import com.dividendstream.app.data.repository.DividendRepository
import com.dividendstream.app.data.repository.LedgerRepository
import com.dividendstream.app.data.repository.PortfolioRepository
import com.dividendstream.app.domain.AccumulationStream
import com.dividendstream.app.ui.ledger.LedgerStreams
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
    /**
     * Why the displayed copy is stale, once that is known. Null while the saved copy is on
     * screen and the request that will replace it is still in flight -- "checking" and "the
     * check failed" are different things to tell someone.
     */
    val staleError: AppError? = null,
    val error: AppError? = null,
    /** A newer published release, or null when there is nothing to tell the user. */
    val newerRelease: String? = null,
    /**
     * The ledger, for the combined pace alone. Absent when it has not loaded or has nothing
     * in it, and its absence is never an error here -- the dashboard is a dividend screen
     * first, and the ledger has its own tab to report its own problems on.
     */
    val ledger: LedgerDto? = null,
    /** The ledger's flows as counter parameters, so the home screen can tick them too. */
    val ledgerStreams: LedgerStreams = LedgerStreams(),
    /** For the home screen's total. Absent when it has not loaded; never an error here. */
    val portfolio: PortfolioDto? = null,
) {
    val isEmpty: Boolean get() = snapshot != null && snapshot.streams.isEmpty()
}

class DashboardViewModel(
    private val dividendRepository: DividendRepository,
    private val ledgerRepository: LedgerRepository,
    private val portfolioRepository: PortfolioRepository,
    private val appInfoRepository: AppInfoRepository,
    val serverClock: ServerClock,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardUiState())
    val state = _state.asStateFlow()

    init {
        refresh()
        checkForUpdate()
    }

    /**
     * Loads the ledger for the combined figure only, and stays silent when it fails.
     *
     * A separate request rather than a field on the dividend response: the two are different
     * parts of the backend with different shapes, and folding one into the other to save a
     * round trip would tie them together for good. It runs alongside the dividend load, so it
     * costs waiting time only if it is the slower of the two.
     */
    private fun loadLedger() {
        viewModelScope.launch {
            val result = ledgerRepository.ledger()
            if (result is AppResult.Success) {
                val loaded = result.data.value
                _state.update {
                    it.copy(
                        ledger = loaded,
                        ledgerStreams = LedgerStreams(
                            income = loaded.flows.filter { f -> f.direction == "INCOME" }
                                .mapNotNull { f -> f.toAccumulationStream() },
                            expense = loaded.flows.filter { f -> f.direction == "EXPENSE" }
                                .mapNotNull { f -> f.toAccumulationStream() },
                        ),
                    )
                }
            }
        }
    }

    /**
     * Asked once per launch, and never surfaced as an error. An update notice is the least
     * urgent thing on the screen, so if the backend cannot answer, the user hears nothing.
     *
     * It has a second job that is not incidental and must not be removed with it: this is the
     * request that wakes a sleeping server, and it goes out the moment the app opens. Whatever
     * the person does next -- reading the dashboard, looking for a stock -- happens while the
     * container is already starting instead of afterwards. It is deliberately the version
     * endpoint, which touches no database and needs no session, so it is the cheapest thing
     * that can possibly do the waking.
     */
    private fun checkForUpdate() {
        viewModelScope.launch {
            val newer = runCatching { appInfoRepository.newerRelease() }.getOrNull() ?: return@launch
            _state.update { it.copy(newerRelease = newer) }
        }
    }

    fun dismissUpdateNotice() = _state.update { it.copy(newerRelease = null) }

    /**
     * Pulls a fresh snapshot. Cheap on the backend -- a single read, no writes -- because the
     * accumulating value is derived rather than stored, so calling this on every resume is
     * fine.
     *
     * [fromPull] drives the pull-to-refresh spinner, which has to keep turning while data is
     * already on screen — the reason it cannot simply reuse [DashboardUiState.isLoading].
     */
    /**
     * Loads the portfolio for the home screen's total, and stays silent when it fails.
     *
     * Like the ledger above: this screen is a summary, and each part it cannot fetch simply
     * goes missing from the total rather than replacing the whole screen with an error. The
     * portfolio tab reports its own problems.
     */
    private fun loadPortfolio() {
        viewModelScope.launch {
            val result = portfolioRepository.portfolio()
            if (result is AppResult.Success) {
                _state.update { it.copy(portfolio = result.data.value) }
            }
        }
    }

    fun refresh(fromPull: Boolean = false) {
        loadLedger()
        loadPortfolio()
        viewModelScope.launch {
            // Paint the saved copy before asking the server, not after giving up on it. The
            // backend sleeps between uses and can take two minutes to wake, and there is no
            // reason to stare at a spinner while the figures sit on the device -- they are
            // derived from timestamps, so they are already counting and already correct.
            if (_state.value.snapshot == null) {
                dividendRepository.cachedLive()?.let { cached ->
                    _state.update {
                        it.copy(
                            isLoading = false,
                            snapshot = cached.value,
                            streams = cached.value.streams.map { s -> s.toAccumulationStream() },
                            isStale = true,
                            cachedAt = cached.cachedAt,
                            staleError = null,
                        )
                    }
                }
            }

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
                        staleError = result.data.staleError,
                        error = null,
                    )
                }

                // With a saved copy already on screen, the failure explains why it is stale
                // rather than replacing the figures with an error the user cannot act on.
                is AppResult.Failure -> _state.update {
                    if (it.snapshot != null) {
                        it.copy(isLoading = false, isRefreshing = false, staleError = result.error)
                    } else {
                        it.copy(isLoading = false, isRefreshing = false, error = result.error)
                    }
                }
            }
        }
    }
}
