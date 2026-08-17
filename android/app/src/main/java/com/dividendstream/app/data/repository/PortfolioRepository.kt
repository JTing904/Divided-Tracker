package com.dividendstream.app.data.repository

import com.dividendstream.app.core.AppResult
import com.dividendstream.app.data.local.SnapshotCache
import com.dividendstream.app.data.remote.CreateHoldingRequest
import com.dividendstream.app.data.remote.DividendStreamApi
import com.dividendstream.app.data.remote.HoldingDto
import com.dividendstream.app.data.remote.ManualDividendRequest
import com.dividendstream.app.data.remote.PortfolioDto
import com.dividendstream.app.data.remote.StockDetailDto
import com.dividendstream.app.data.remote.StockSummaryDto
import com.dividendstream.app.data.remote.UpdateHoldingRequest
import com.dividendstream.app.data.remote.apiCall
import kotlinx.serialization.json.Json
import java.math.BigDecimal

class PortfolioRepository(
    private val api: DividendStreamApi,
    private val snapshotCache: SnapshotCache,
    private val json: Json,
) {

    suspend fun portfolio(): AppResult<Cached<PortfolioDto>> =
        when (val result = apiCall(json) { api.portfolio() }) {
            is AppResult.Success -> {
                snapshotCache.savePortfolio(result.data)
                AppResult.Success(Cached(result.data))
            }

            is AppResult.Failure -> {
                if (result.error.isAuthFailure) {
                    result
                } else {
                    val cached = snapshotCache.readPortfolio()
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

    suspend fun searchStocks(query: String): AppResult<List<StockSummaryDto>> =
        apiCall(json) { api.searchStocks(query) }

    suspend fun stockDetail(symbol: String): AppResult<StockDetailDto> =
        apiCall(json) { api.stockDetail(symbol) }

    suspend fun addHolding(
        symbol: String,
        quantity: BigDecimal,
        averagePrice: BigDecimal,
        manualDividend: ManualDividendRequest? = null,
    ): AppResult<HoldingDto> = apiCall(json) {
        api.addHolding(CreateHoldingRequest(symbol, quantity, averagePrice, manualDividend))
    }

    suspend fun updateHolding(
        id: String,
        quantity: BigDecimal,
        averagePrice: BigDecimal,
    ): AppResult<HoldingDto> = apiCall(json) {
        api.updateHolding(id, UpdateHoldingRequest(quantity, averagePrice))
    }

    suspend fun deleteHolding(id: String): AppResult<Unit> = apiCall(json) { api.deleteHolding(id) }
}
