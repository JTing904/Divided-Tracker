package com.dividendstream.api.portfolio

import com.dividendstream.api.dividend.DividendFrequency
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CreateHoldingRequest(
    @field:NotBlank(message = "Stock symbol is required")
    @field:Size(max = 32, message = "Stock symbol is too long")
    val symbol: String,

    @field:NotNull(message = "Number of shares is required")
    @field:DecimalMin(value = "0.0001", message = "Enter at least 0.0001 shares")
    @field:DecimalMax(value = "999999999999.9999", message = "That is more shares than we can track")
    @field:Digits(integer = 12, fraction = 4, message = "Shares may have at most 4 decimal places")
    val quantity: BigDecimal,

    @field:NotNull(message = "Average purchase price is required")
    @field:DecimalMin(value = "0.0000", message = "Price cannot be negative")
    @field:Digits(integer = 15, fraction = 4, message = "Price may have at most 4 decimal places")
    val averagePrice: BigDecimal,

    /**
     * Optional. Lets the user supply dividend details themselves for a stock the configured
     * provider does not cover, so the app is usable without a live data feed.
     */
    @field:Valid
    val manualDividend: ManualDividendRequest? = null,
)

data class UpdateHoldingRequest(
    @field:NotNull(message = "Number of shares is required")
    @field:DecimalMin(value = "0.0001", message = "Enter at least 0.0001 shares")
    @field:Digits(integer = 12, fraction = 4, message = "Shares may have at most 4 decimal places")
    val quantity: BigDecimal,

    @field:NotNull(message = "Average purchase price is required")
    @field:DecimalMin(value = "0.0000", message = "Price cannot be negative")
    @field:Digits(integer = 15, fraction = 4, message = "Price may have at most 4 decimal places")
    val averagePrice: BigDecimal,
)

data class ManualDividendRequest(
    @field:NotNull(message = "Dividend per share is required")
    @field:DecimalMin(value = "0.00000001", message = "Dividend per share must be greater than zero")
    @field:Digits(integer = 11, fraction = 8, message = "Dividend per share is too precise")
    val dividendPerShare: BigDecimal,

    @field:NotNull(message = "Dividend frequency is required")
    val frequency: DividendFrequency,

    @field:NotNull(message = "Ex-dividend date is required")
    val exDate: LocalDate,

    val recordDate: LocalDate? = null,

    @field:NotNull(message = "Payment date is required")
    val paymentDate: LocalDate,
)

data class HoldingResponse(
    val id: UUID,
    val stockId: UUID,
    val symbol: String,
    val companyName: String,
    val exchange: String,
    val currency: String,
    val sector: String?,
    val quantity: BigDecimal,
    val averagePrice: BigDecimal,
    val currentPrice: BigDecimal?,
    val costBasis: BigDecimal,
    val marketValue: BigDecimal?,
    val dividendPerShare: BigDecimal?,
    val dividendYieldPercent: BigDecimal?,
    /** Estimated, for the cycles currently in flight. Not received income. */
    val expectedDividend: BigDecimal,
    val nextPaymentDate: LocalDate?,
)

data class PortfolioResponse(
    val holdings: List<HoldingResponse>,
    val totalCostBasis: BigDecimal,
    val totalMarketValue: BigDecimal,
    val totalExpectedDividend: BigDecimal,
    val currency: String,
)
