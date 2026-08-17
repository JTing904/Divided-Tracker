package com.dividendstream.api.dividend

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

interface DividendRepository : JpaRepository<DividendEntity, UUID> {

    @EntityGraph(attributePaths = ["stock"])
    fun findAllByStockIdOrderByPaymentDateDesc(stockId: UUID): List<DividendEntity>

    fun findByStockIdAndExDateAndPaymentDate(
        stockId: UUID,
        exDate: LocalDate,
        paymentDate: LocalDate,
    ): Optional<DividendEntity>

    /** Cycles worth generating entitlements for: everything paying on or after [from]. */
    @EntityGraph(attributePaths = ["stock"])
    @Query("SELECT d FROM DividendEntity d WHERE d.stock.id = :stockId AND d.paymentDate >= :from ORDER BY d.paymentDate ASC")
    fun findForStockPayingFrom(
        @Param("stockId") stockId: UUID,
        @Param("from") from: LocalDate,
    ): List<DividendEntity>

    /** Latest declared cycle for a stock, used to show headline dividend figures. */
    @EntityGraph(attributePaths = ["stock"])
    fun findFirstByStockIdOrderByPaymentDateDesc(stockId: UUID): Optional<DividendEntity>

    /** Batched form of the above: one query for a whole portfolio, rather than one per row. */
    @Query(
        """
        SELECT d FROM DividendEntity d
        WHERE d.stock.id IN :stockIds
        ORDER BY d.paymentDate DESC
        """,
    )
    fun findAllForStocks(@Param("stockIds") stockIds: Collection<UUID>): List<DividendEntity>
}
