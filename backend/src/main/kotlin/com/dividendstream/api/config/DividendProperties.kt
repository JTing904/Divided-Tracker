package com.dividendstream.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dividend-stream.dividends")
data class DividendProperties(
    /**
     * When a holding is added, also create entitlements for cycles that already paid out,
     * marking them settled so dividend history is populated immediately.
     *
     * This *assumes* the position was held through those cycles, which is a reasonable
     * default for a manually-entered portfolio but is not a fact. Set to false once broker
     * synchronisation can establish real ownership dates.
     */
    val backfillSettledCycles: Boolean = true,

    /** How far back backfill reaches. */
    val backfillWindowDays: Long = 400,
)
