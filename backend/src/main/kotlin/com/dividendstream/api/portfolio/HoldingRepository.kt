package com.dividendstream.api.portfolio

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface HoldingRepository : JpaRepository<HoldingEntity, UUID> {

    /**
     * Every read is scoped by `userId`. Callers must use these rather than [findById], so
     * that one user can never load another user's position by guessing an id.
     */
    @EntityGraph(attributePaths = ["stock"])
    fun findAllByUserIdOrderByCreatedAtAsc(userId: UUID): List<HoldingEntity>

    @EntityGraph(attributePaths = ["stock"])
    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<HoldingEntity>

    @EntityGraph(attributePaths = ["stock"])
    fun findByUserIdAndStockId(userId: UUID, stockId: UUID): Optional<HoldingEntity>

    @EntityGraph(attributePaths = ["stock"])
    fun findAllByStockId(stockId: UUID): List<HoldingEntity>

    fun countByUserId(userId: UUID): Long
}
