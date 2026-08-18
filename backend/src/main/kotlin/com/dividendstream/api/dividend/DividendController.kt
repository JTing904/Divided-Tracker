package com.dividendstream.api.dividend

import com.dividendstream.api.security.AuthPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/dividends")
class DividendController(private val liveDividendService: LiveDividendService) {

    /**
     * Snapshot of every accumulating dividend plus the parameters the client needs to keep
     * counting on its own. Safe to poll: it performs no writes.
     */
    @GetMapping("/live")
    fun live(@AuthenticationPrincipal principal: AuthPrincipal): LiveDividendResponse =
        liveDividendService.live(principal.userId)

    /** Everything not yet settled, earliest payment first. Backs the dividend calendar. */
    @GetMapping("/upcoming")
    fun upcoming(@AuthenticationPrincipal principal: AuthPrincipal): UpcomingDividendsResponse =
        liveDividendService.upcoming(principal.userId)

    @GetMapping("/history")
    fun history(@AuthenticationPrincipal principal: AuthPrincipal): DividendHistoryResponse =
        liveDividendService.history(principal.userId)

    /**
     * Records the day a dividend actually arrived.
     *
     * The one thing in this application only the holder can know: no free source publishes when
     * a Bursa issuer paid, so until somebody says, every payment date here is an estimate.
     */
    @PostMapping("/{id}/received")
    fun confirmReceived(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable("id") id: UUID,
        @Valid @RequestBody request: ConfirmReceivedRequest,
    ): DividendResponse {
        liveDividendService.confirmReceived(principal.userId, id, request.receivedOn)
        return liveDividendService.detail(principal.userId, id)
    }

    @GetMapping("/{id}")
    fun detail(
        @AuthenticationPrincipal principal: AuthPrincipal,
        @PathVariable("id") id: UUID,
    ): DividendResponse = liveDividendService.detail(principal.userId, id)
}
