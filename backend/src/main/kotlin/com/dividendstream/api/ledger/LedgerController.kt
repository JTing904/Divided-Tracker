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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth
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
    fun ledger(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @RequestParam(name = "period", defaultValue = "MONTH") period: LedgerPeriod,
        /** `2026-07`, to look back at a finished month. Absent means the month it is now. */
        @RequestParam(name = "month", required = false) month: String?,
    ): LedgerResponse = ledgerService.ledger(
        principal.userId,
        period,
        month?.takeIf { it.isNotBlank() }?.let {
            // A month that will not parse is a broken link or a stale client, and answering
            // with this month is more use than a 400 on a screen that has no way to say so.
            runCatching { YearMonth.parse(it) }.getOrNull()
        },
    )

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

    /**
     * Put money into a fund, or take some out. A withdrawal past the balance is refused, and
     * the refusal says how much is actually there.
     */
    @PostMapping("/funds/{id}/movements")
    fun saveFundMovement(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable("id") id: UUID,
        @Valid @RequestBody request: SaveFundMovementRequest,
    ): FundMovementResponse = ledgerService.saveFundMovement(principal.userId, id, request)

    @DeleteMapping("/movements/{id}")
    fun deleteFundMovement(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable("id") id: UUID,
    ): ResponseEntity<Void> {
        ledgerService.deleteFundMovement(principal.userId, id)
        return ResponseEntity.noContent().build()
    }
}
