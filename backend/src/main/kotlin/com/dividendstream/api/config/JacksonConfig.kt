package com.dividendstream.api.config

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

@Configuration
class JacksonConfig {

    /**
     * Emits every [BigDecimal] as a JSON *string*.
     *
     * A JSON number is an IEEE-754 double to most parsers, which would silently reintroduce
     * the floating-point error this whole system is designed to avoid: RM0.000020576132
     * would not survive the round trip. Strings preserve the value and the scale exactly.
     */
    @Bean
    fun moneyJacksonModule(): SimpleModule =
        SimpleModule("MoneyModule").addSerializer(BigDecimal::class.java, PlainBigDecimalSerializer())
}

class PlainBigDecimalSerializer : JsonSerializer<BigDecimal>() {
    override fun serialize(value: BigDecimal, gen: JsonGenerator, serializers: SerializerProvider) {
        gen.writeString(value.toPlainString())
    }
}
