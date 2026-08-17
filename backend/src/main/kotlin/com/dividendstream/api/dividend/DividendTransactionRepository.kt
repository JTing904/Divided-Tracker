package com.dividendstream.api.dividend

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface DividendTransactionRepository : JpaRepository<DividendTransactionEntity, UUID> {

    @EntityGraph(attributePaths = ["stock"])
    fun findAllByUserIdAndStatusInOrderByPaymentDateAsc(
        userId: UUID,
        statuses: Collection<DividendStatus>,
    ): List<DividendTransactionEntity>

    @EntityGraph(attributePaths = ["stock"])
    fun findAllByUserIdAndStatusOrderByPaymentDateDesc(
        userId: UUID,
        status: DividendStatus,
    ): List<DividendTransactionEntity>

    @EntityGraph(attributePaths = ["stock"])
    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<DividendTransactionEntity>

    fun findByUserIdAndDividendId(userId: UUID, dividendId: UUID): Optional<DividendTransactionEntity>

    fun findAllByHoldingId(holdingId: UUID): List<DividendTransactionEntity>

    fun countByUserIdAndStatusIn(userId: UUID, statuses: Collection<DividendStatus>): Long

    /** Total actually received. Uses `paid_amount`, never the estimate. */
    @Query(
        """
        SELECT COALESCE(SUM(t.paidAmount), 0)
        FROM DividendTransactionEntity t
        WHERE t.userId = :userId AND t.status = com.dividendstream.api.dividend.DividendStatus.PAID
        """,
    )
    fun sumPaidAmountForUser(@Param("userId") userId: UUID): BigDecimal

    /** Drives the settlement job: windows that have closed but are not yet marked paid. */
    @EntityGraph(attributePaths = ["stock"])
    @Query(
        """
        SELECT t FROM DividendTransactionEntity t
        WHERE t.status IN :statuses AND t.accumulationEnd <= :now
        """,
    )
    fun findMaturedBefore(
        @Param("statuses") statuses: Collection<DividendStatus>,
        @Param("now") now: Instant,
    ): List<DividendTransactionEntity>

    /** Drives the status-transition job: UPCOMING rows whose ex-date has arrived. */
    @Query(
        """
        SELECT t FROM DividendTransactionEntity t
        WHERE t.status = com.dividendstream.api.dividend.DividendStatus.UPCOMING
          AND t.accumulationStart <= :now
        """,
    )
    fun findStartedBefore(@Param("now") now: Instant): List<DividendTransactionEntity>

    /**
     * Dividends that already have settled money recorded against them.
     *
     * `dividend_transactions.dividend_id` cascades on delete, so removing a dividend row
     * silently removes the user's received-income history with it. Reconciliation consults
     * this before deleting anything.
     */
    @Query(
        """
        SELECT DISTINCT t.dividendId FROM DividendTransactionEntity t
        WHERE t.dividendId IN :dividendIds AND t.status = com.dividendstream.api.dividend.DividendStatus.PAID
        """,
    )
    fun findSettledDividendIds(@Param("dividendIds") dividendIds: Collection<UUID>): List<UUID>
}
