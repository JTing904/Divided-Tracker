package com.dividendstream.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.dividendstream.app.core.Precision
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.core.currencySymbol
import com.dividendstream.app.core.formatAmount
import com.dividendstream.app.domain.AccumulationCalculator
import com.dividendstream.app.domain.AccumulationStream
import com.dividendstream.app.ui.theme.LiveCounterFigure
import java.math.BigDecimal

/**
 * Recomputes the accrued total once per frame from the corrected clock.
 *
 * Frame-driven rather than timer-driven for two reasons: the value is smooth instead of
 * stepping once a second, and `withFrameNanos` simply stops being resumed when the app is
 * not drawing, so a backgrounded app does no work.
 *
 * The number is never incremented -- each frame it is recalculated from the timestamps. That
 * is what keeps it honest across pauses, backgrounding and process death, and what makes it
 * agree with the server's own figure on the next refresh.
 */
@Composable
fun rememberAccruedAmount(
    streams: List<AccumulationStream>,
    clock: ServerClock,
): State<BigDecimal> {
    val amount = remember { mutableStateOf(BigDecimal.ZERO) }

    LaunchedEffect(streams, clock) {
        // Paint the correct value immediately rather than waiting for the first frame.
        amount.value = AccumulationCalculator.totalAccruedAt(streams, clock.now())
        while (true) {
            withFrameNanos { }
            amount.value = AccumulationCalculator.totalAccruedAt(streams, clock.now())
        }
    }

    return amount
}

/**
 * The headline money figure, with the fast-moving trailing digits dimmed so the eye rests
 * on the ringgit and cents while still seeing the movement.
 */
@Composable
fun LiveAmountText(
    amount: BigDecimal,
    currency: String,
    modifier: Modifier = Modifier,
    decimals: Int = Precision.LIVE,
    steadyDecimals: Int = 2,
    style: TextStyle = LiveCounterFigure,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    trailingColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
) {
    val formatted = amount.formatAmount(decimals)
    val decimalPoint = formatted.indexOf('.')
    val steadyEnd = if (decimalPoint < 0) formatted.length else decimalPoint + 1 + steadyDecimals

    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = primaryColor, fontSize = style.fontSize * 0.55f)) {
            append(currencySymbol(currency))
            append(" ")
        }
        withStyle(SpanStyle(color = primaryColor)) {
            append(formatted.substring(0, steadyEnd.coerceAtMost(formatted.length)))
        }
        if (steadyEnd < formatted.length) {
            withStyle(SpanStyle(color = trailingColor)) {
                append(formatted.substring(steadyEnd))
            }
        }
    }

    Text(text = text, style = style, modifier = modifier, maxLines = 1)
}

/**
 * A once-per-second clock reading, for countdowns and other text that changes slowly.
 * Kept separate from the frame loop so a countdown does not force 60 recompositions a second.
 */
@Composable
fun rememberSecondTicker(clock: ServerClock): State<java.time.Instant> {
    val now = remember { mutableStateOf(clock.now()) }
    LaunchedEffect(clock) {
        while (true) {
            now.value = clock.now()
            kotlinx.coroutines.delay(1_000)
        }
    }
    return now
}

/** Convenience wrapper: ticks and renders in one call. */
@Composable
fun LiveAccrualCounter(
    streams: List<AccumulationStream>,
    currency: String,
    clock: ServerClock,
    modifier: Modifier = Modifier,
    style: TextStyle = LiveCounterFigure,
) {
    val amount by rememberAccruedAmount(streams, clock)
    LiveAmountText(amount = amount, currency = currency, modifier = modifier, style = style)
}
