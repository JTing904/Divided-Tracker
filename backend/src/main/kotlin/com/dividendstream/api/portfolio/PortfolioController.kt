package com.dividendstream.api.portfolio

import com.dividendstream.api.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Every method scopes its work to `principal.userId`. Holding ids arriving in the path are
 * only ever used together with that id, so a guessed id resolves to nothing.
 */
@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(private val portfolioService: PortfolioService) {

    @GetMapping
    fun portfolio(@AuthenticationPrincipal principal: AuthPrincipal): PortfolioResponse =
        portfolioService.portfolio(principal.userId)

    @PostMapping
    fun addHolding(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: CreateHoldingRequest,
    ): ResponseEntity<HoldingResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(portfolioService.addHolding(principal.userId, request))

    @PutMapping("/{id}")
    fun updateHolding(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable("id") id: UUID,
        @Valid @RequestBody request: UpdateHoldingRequest,
    ): HoldingResponse = portfolioService.updateHolding(principal.userId, id, request)

    @DeleteMapping("/{id}")
    fun deleteHolding(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable("id") id: UUID,
    ): ResponseEntity<Void> {
        portfolioService.deleteHolding(principal.userId, id)
        return ResponseEntity.noContent().build()
    }
}
