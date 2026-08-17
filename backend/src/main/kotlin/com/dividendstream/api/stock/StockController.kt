package com.dividendstream.api.stock

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/stocks")
class StockController(private val stockService: StockService) {

    /** Stocks already known locally. Useful as a starting list before the user searches. */
    @GetMapping
    fun list(): List<StockSummaryResponse> = stockService.listKnown()

    @GetMapping("/search")
    fun search(@RequestParam("query") query: String): List<StockSummaryResponse> =
        stockService.search(query)

    @GetMapping("/{symbol}")
    fun detail(@PathVariable("symbol") symbol: String): StockDetailResponse =
        stockService.detail(symbol)
}
