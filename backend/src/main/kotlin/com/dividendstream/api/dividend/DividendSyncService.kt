package com.dividendstream.api.dividend

import com.dividendstream.api.marketdata.MarketDataService
import com.dividendstream.api.marketdata.ProviderDividend
import com.dividendstream.api.marketdata.YahooDividendCalendar
import com.dividendstream.api.stock.StockEntity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Copies declared dividend cycles from the configured provider into the local `dividends`
 * table. Provider data is market-wide; the per-user amounts derived from it are created by
 * [DividendTransactionService].
 */
@Service
class DividendSyncService(
    private val dividendRepository: DividendRepository,
    private val transactionRepository: DividendTransactionRepository,
    private val marketDataService: MarketDataService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun syncStock(stock: StockEntity): List<DividendEntity> {
        val cycles = try {
            marketDataService.dividends(stock.symbol)
        } catch (ex: Exception) {
            // Missing dividend data must not block adding a holding; the user can still
            // enter it manually and a later sync can fill it in.
            log.warn("Dividend sync failed for {}: {}", stock.symbol, ex.message)
            return emptyList()
        }
        // Retime every estimate to what this issuer has actually been observed doing. The
        // provider works from a fixed default lag because it knows nothing about this user's
        // confirmations; they live here.
        val retimed = retimeToObservedLag(stock, cycles)
        val current = retimed.mapNotNull { upsert(stock, it) }
        reconcile(stock, current)
        return current
    }

    /**
     * Removes provider-supplied cycles that this sync did not produce.
     *
     * Without this the `dividends` table only ever grows. Two things put rows in it that no
     * longer correspond to anything: a projected cycle that a later sync supersedes with the
     * real announcement, and rows left behind by a previously configured provider. Both show
     * up to the user as the same stock paying twice.
     *
     * Two categories are never touched: cycles the user entered themselves, and cycles with
     * settled money against them -- deleting one of those would cascade into their received
     * income, which is the one figure in this application that must never be inferred away.
     */
    private fun reconcile(stock: StockEntity, current: List<DividendEntity>) {
        val keep = current.mapTo(mutableSetOf()) { it.id }

        val stale = dividendRepository.findAllByStockIdOrderByPaymentDateDesc(stock.id)
            .filter { it.id !in keep && it.source != MANUAL_SOURCE }
        if (stale.isEmpty()) return

        val settled = transactionRepository.findSettledDividendIds(stale.map { it.id }).toSet()
        val removable = stale.filterNot { it.id in settled }
        if (removable.isEmpty()) return

        log.info(
            "Removing {} superseded dividend row(s) for {}; {} kept because they are settled",
            removable.size, stock.symbol, stale.size - removable.size,
        )
        dividendRepository.deleteAll(removable)
    }

    private companion object {
        const val MANUAL_SOURCE = "manual"
    }

    /** Records a cycle the user typed in themselves, for stocks the provider does not cover. */
    @Transactional
    /**
     * Re-estimates payment dates from the lag this issuer has actually shown.
     *
     * Cycles whose real date somebody confirmed are left exactly where they are: an observation
     * is not something to re-estimate. Everything else moves onto the observed median, so one
     * confirmation improves the dates of every cycle that is still a guess -- including the
     * projected one the live counter runs against.
     */
    private fun retimeToObservedLag(
        stock: StockEntity,
        cycles: List<ProviderDividend>,
    ): List<ProviderDividend> {
        val confirmed = dividendRepository.findConfirmedForStock(stock.id)
        val lag = PaymentLag.infer(
            confirmed.mapNotNull { cycle ->
                cycle.actualPaymentDate?.let { PaymentLag.Observation(cycle.exDate, it) }
            },
        ) ?: return cycles

        val known = confirmed.associate { it.exDate to it.actualPaymentDate }

        return cycles.map { cycle ->
            val actual = known[cycle.exDate]
            if (actual != null) {
                cycle.copy(paymentDate = actual)
            } else {
                cycle.copy(paymentDate = YahooDividendCalendar.estimatePaymentDate(cycle.exDate, lag))
            }
        }
    }

    fun recordManualDividend(stock: StockEntity, cycle: ProviderDividend): DividendEntity =
        upsert(stock, cycle, source = MANUAL_SOURCE)
            ?: error("Manual dividend could not be stored")

    private fun upsert(
        stock: StockEntity,
        cycle: ProviderDividend,
        source: String = cycle.sourceTag ?: marketDataService.providerName,
    ): DividendEntity? {
        if (!cycle.paymentDate.isAfter(cycle.exDate)) {
            log.warn(
                "Ignoring dividend for {} with payment date {} not after ex-date {}",
                stock.symbol, cycle.paymentDate, cycle.exDate,
            )
            return null
        }

        // Matched on the ex-date, which is what identifies a cycle. Including the payment date
        // was safe only while it never changed; now that a better estimate can replace an older
        // one, it would file the revision as a second dividend and show the stock paying twice.
        // A manual row is the user's own and is matched exactly, so a sync never claims it.
        val entity = if (source == MANUAL_SOURCE) {
            dividendRepository
                .findByStockIdAndExDateAndPaymentDate(stock.id, cycle.exDate, cycle.paymentDate)
                .orElseGet { DividendEntity(stock = stock, exDate = cycle.exDate, paymentDate = cycle.paymentDate) }
        } else {
            dividendRepository.findProviderCycles(stock.id, cycle.exDate).firstOrNull()
                ?: DividendEntity(stock = stock, exDate = cycle.exDate, paymentDate = cycle.paymentDate)
        }

        entity.paymentDate = cycle.paymentDate

        entity.dividendPerShare = cycle.dividendPerShare
        entity.currency = cycle.currency
        entity.frequency = cycle.frequency
        entity.recordDate = cycle.recordDate
        entity.source = source

        return dividendRepository.save(entity)
    }
}
