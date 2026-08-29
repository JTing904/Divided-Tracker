package com.dividendstream.api.ledger

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Every read is scoped by `userId`. Callers must use these rather than [JpaRepository.findById],
 * so that one person can never load another's figures by guessing an id.
 */
interface CashFlowRepository : JpaRepository<CashFlowEntity, UUID> {

    fun findAllByUserIdOrderByCreatedAtAsc(userId: UUID): List<CashFlowEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<CashFlowEntity>

    fun countByUserId(userId: UUID): Long
}

interface LedgerEntryRepository : JpaRepository<LedgerEntryEntity, UUID> {

    fun findAllByUserIdAndOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<LedgerEntryEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<LedgerEntryEntity>
}

interface FundRepository : JpaRepository<FundEntity, UUID> {

    fun findAllByUserIdOrderByPositionAscCreatedAtAsc(userId: UUID): List<FundEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<FundEntity>
}

interface FundMovementRepository : JpaRepository<FundMovementEntity, UUID> {

    fun findAllByUserIdOrderByOccurredOnDescCreatedAtDesc(userId: UUID): List<FundMovementEntity>

    fun findAllByFundIdOrderByOccurredOnDescCreatedAtDesc(fundId: UUID): List<FundMovementEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<FundMovementEntity>
}
