package com.dividendstream.api.stock

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional
import java.util.UUID

interface StockRepository : JpaRepository<StockEntity, UUID> {

    fun findByExchangeAndSymbol(exchange: String, symbol: String): Optional<StockEntity>

    @Query("SELECT s FROM StockEntity s WHERE lower(s.symbol) = lower(:symbol)")
    fun findBySymbolIgnoreCase(@Param("symbol") symbol: String): List<StockEntity>

    @Query(
        """
        SELECT s FROM StockEntity s
        WHERE lower(s.symbol) LIKE lower(concat('%', :query, '%'))
           OR lower(s.companyName) LIKE lower(concat('%', :query, '%'))
        ORDER BY s.companyName ASC
        """,
    )
    fun search(@Param("query") query: String, pageable: Pageable): List<StockEntity>
}
