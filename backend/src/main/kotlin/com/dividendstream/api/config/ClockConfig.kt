package com.dividendstream.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class ClockConfig {

    /**
     * Time is injected rather than read from [java.time.Instant.now] statically, so tests can
     * wind the clock forward and assert accumulation behaviour without sleeping.
     *
     * UTC everywhere: all timestamps are stored and computed in UTC and only rendered in a
     * local zone by the client.
     */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
