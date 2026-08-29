package com.dividendstream.api.ledger

import com.dividendstream.api.security.AuthPrincipal
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Every method scopes its work to `principal.userId`. Ids arriving in the path or the body are
 * only ever used together with that id, so one belonging to somebody else resolves to nothing.
 */
@RestController
@RequestMapping("/api/ledger")
class LedgerController(private val ledgerService: LedgerService) {

    /**
     * Everything the ledger screen shows. Poll-safe: the backend performs no writes to serve it,
     * because the accruing figures are derived from stored parameters and the clock.
     */
    @GetMapping
    fun ledger(@AuthenticationPrincipal principal: AuthPrincipal): LedgerResponse =
        ledgerService.ledger(principal.userId)

    /** Create or update a recurring flow. Sending the same id twice records it once. */
    @PostMapping("/flows")
    fun saveFlow(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: SaveCashFlowRequest,
    ): CashFlowResponse = ledgerService.saveFlow(principal.userId, request)

    @DeleteMapping("/flows/{id}")
    fun deleteFlow(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable("id") id: UUID,
    ): ResponseEntity<Void> {
        ledgerService.deleteFlow(principal.userId, id)
        return ResponseEntity.noContent().build()
    }

    /** Record one thing that happened. Sending the same id twice records it once. */
    @PostMapping("/entries")
    fun saveEntry(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: SaveLedgerEntryRequest,
    ): LedgerEntryResponse = ledgerService.saveEntry(principal.userId, request)

    @DeleteMapping("/entries/{id}")
    fun deleteEntry(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable("id") id: UUID,
    ): ResponseEntity<Void> {
        ledgerService.deleteEntry(principal.userId, id)
        return ResponseEntity.noContent().build()
    }

    /**
     * Create or update a fund. Refused when the shares would add up past 100%, with a message
     * saying how much is still free.
     */
    @PostMapping("/funds")
    fun saveFund(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @Valid @RequestBody request: SaveFundRequest,
    ): FundResponse = ledgerService.saveFund(principal.userId, request)

    @DeleteMapping("/funds/{id}")
    fun deleteFund(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable("id") id: UUID,
    ): ResponseEntity<Void> {
        ledgerService.deleteFund(principal.userId, id)
        return ResponseEntity.noContent().build()
    }
}
