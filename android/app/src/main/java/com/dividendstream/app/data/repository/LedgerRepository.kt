package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.local.SnapshotCache
import com.dividendstream.app.data.remote.CashFlowDto
import com.dividendstream.app.data.remote.DividendStreamApi
import com.dividendstream.app.data.remote.FundDto
import com.dividendstream.app.data.remote.FundMovementDto
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.data.remote.LedgerEntryDto
import com.dividendstream.app.data.remote.SaveCashFlowRequest
import com.dividendstream.app.data.remote.SaveFundMovementRequest
import com.dividendstream.app.data.remote.SaveFundRequest
import com.dividendstream.app.data.remote.SaveLedgerEntryRequest
import com.dividendstream.app.data.remote.apiCall
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.LocalDate

class LedgerRepository(
    private val api: DividendStreamApi,
    private val snapshotCache: SnapshotCache,
    private val json: Json,
) {

    suspend fun ledger(
        period: String = "MONTH",
        month: String? = null,
    ): AppResult<Cached<LedgerDto>> =
        when (val result = apiCall(json) { api.ledger(period, month) }) {
            is AppResult.Success -> {
                // Only this month is cached. A saved day would be yesterday's by morning, and
                // a saved July is a finished month nobody is waiting on -- neither is what the
                // cache is for, which is having something to count while the server wakes.
                if (period == "MONTH" && month == null) snapshotCache.saveLedger(result.data)
                AppResult.Success(Cached(result.data))
            }

            is AppResult.Failure -> {
                if (result.error.isAuthFailure || period != "MONTH" || month != null) {
                    result
                } else {
                    val cached = snapshotCache.readLedger()
                    if (cached == null) {
                        result
                    } else {
                        AppResult.Success(
                            Cached(
                                value = cached.value,
                                isStale = true,
                                cachedAt = cached.cachedAt,
                                staleError = result.error,
                            ),
                        )
                    }
                }
            }
        }

    /**
     * The saved ledger, read without touching the network.
     *
     * Painted before the request goes out, not after it fails. The counter is a function of
     * timestamps, so a saved copy is still counting and still correct while the server wakes.
     * See DividendRepository.cachedLive.
     */
    suspend fun cachedLedger(): Cached<LedgerDto>? =
        snapshotCache.readLedger()?.let { cached ->
            Cached(value = cached.value, isStale = true, cachedAt = cached.cachedAt)
        }

    /**
     * [id] names the save so that sending it twice records one flow. Callers generate it once,
     * before the first attempt, and reuse it on every retry.
     */
    suspend fun saveCashFlow(
        id: String?,
        name: String,
        direction: String,
        amount: BigDecimal,
        period: String,
        category: String?,
        arrivesOn: Int?,
        startsOn: LocalDate?,
        endsOn: LocalDate?,
    ): AppResult<CashFlowDto> = apiCall(json) {
        api.saveCashFlow(
            SaveCashFlowRequest(
                id = id,
                name = name,
                direction = direction,
                amount = amount,
                period = period,
                category = category,
                arrivesOn = arrivesOn,
                startsOn = startsOn,
                endsOn = endsOn,
            ),
        )
    }

    suspend fun deleteCashFlow(id: String): AppResult<Unit> = apiCall(json) { api.deleteCashFlow(id) }

    suspend fun saveEntry(
        id: String?,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate?,
        category: String?,
        note: String?,
    ): AppResult<LedgerEntryDto> = apiCall(json) {
        api.saveLedgerEntry(
            SaveLedgerEntryRequest(
                id = id,
                direction = direction,
                amount = amount,
                occurredOn = occurredOn,
                category = category,
                note = note,
            ),
        )
    }

    suspend fun deleteEntry(id: String): AppResult<Unit> = apiCall(json) { api.deleteLedgerEntry(id) }

    suspend fun saveFund(
        id: String?,
        name: String,
        percent: BigDecimal,
        icon: String?,
    ): AppResult<FundDto> = apiCall(json) {
        api.saveFund(SaveFundRequest(id = id, name = name, percent = percent, icon = icon))
    }

    suspend fun deleteFund(id: String): AppResult<Unit> = apiCall(json) { api.deleteFund(id) }

    suspend fun saveFundMovement(
        fundId: String,
        id: String?,
        direction: String,
        amount: BigDecimal,
        occurredOn: LocalDate?,
        note: String?,
    ): AppResult<FundMovementDto> = apiCall(json) {
        api.saveFundMovement(
            fundId,
            SaveFundMovementRequest(
                id = id,
                direction = direction,
                amount = amount,
                occurredOn = occurredOn,
                note = note,
            ),
        )
    }

    suspend fun deleteFundMovement(id: String): AppResult<Unit> =
        apiCall(json) { api.deleteFundMovement(id) }
}
