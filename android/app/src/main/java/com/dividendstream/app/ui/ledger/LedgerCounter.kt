package com.dividendstream.app.ui.ledger

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.dividendstream.app.core.Precision
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.core.currencySymbol
import com.dividendstream.app.core.formatAmount
import com.dividendstream.app.domain.AccumulationCalculator
import com.dividendstream.app.ui.theme.DividendColors
import com.dividendstream.app.ui.theme.LiveCounterFigure
import java.math.BigDecimal

/**
 * What is left over, right now: income accrued this month minus outgoings accrued this month.
 *
 * Recomputed once per frame from the corrected clock rather than incremented, exactly as the
 * dividend counter is, and through the same calculator. That is what keeps the figure honest
 * across pauses and process death, and what makes it agree with the server's own number on the
 * next refresh instead of jumping.
 *
 * The result is signed. A month where the outgoings win counts *downwards*, and is meant to:
 * a budget that can only ever go up is not a budget.
 */
@Composable
fun rememberNetAccrued(streams: LedgerStreams, clock: ServerClock): State<BigDecimal> {
    val amount = remember { mutableStateOf(BigDecimal.ZERO) }

    LaunchedEffect(streams, clock) {
        fun net(): BigDecimal =
            AccumulationCalculator.totalAccruedAt(streams.income, clock.now())
                .subtract(AccumulationCalculator.totalAccruedAt(streams.expense, clock.now()))

        // Paint the correct value immediately rather than waiting for the first frame.
        amount.value = net()
        while (true) {
            withFrameNanos { }
            amount.value = net()
        }
    }

    return amount
}

/** One side of the counter on its own -- money in, or money out. */
@Composable
fun rememberSideAccrued(
    streams: List<com.dividendstream.app.domain.AccumulationStream>,
    clock: ServerClock,
): State<BigDecimal> {
    val amount = remember { mutableStateOf(BigDecimal.ZERO) }

    LaunchedEffect(streams, clock) {
        amount.value = AccumulationCalculator.totalAccruedAt(streams, clock.now())
        while (true) {
            withFrameNanos { }
            amount.value = AccumulationCalculator.totalAccruedAt(streams, clock.now())
        }
    }

    return amount
}

/**
 * A signed money figure, with the fast-moving trailing digits dimmed.
 *
 * The minus sign goes before the currency symbol -- "-RM 412.50", not "RM -412.50" -- because
 * that is how a negative amount is written, and because at a glance the sign is the first thing
 * that has to register.
 */
@Composable
fun SignedLiveAmountText(
    amount: BigDecimal,
    currency: String,
    modifier: Modifier = Modifier,
    decimals: Int = Precision.LIVE,
    steadyDecimals: Int = 2,
    style: TextStyle = LiveCounterFigure,
    positiveColor: Color = MaterialTheme.colorScheme.primary,
    negativeColor: Color = DividendColors.Danger,
) {
    val negative = amount.signum() < 0
    val colour = if (negative) negativeColor else positiveColor
    val formatted = amount.abs().formatAmount(decimals)
    val decimalPoint = formatted.indexOf('.')
    val steadyEnd = if (decimalPoint < 0) formatted.length else decimalPoint + 1 + steadyDecimals

    // The counter is the largest thing on any screen it appears on, and at RM1,393.123456 on a
    // narrow phone it did not fit -- so it was cut off, leaving a lone "RM" where the headline
    // total should be. It shrinks to fit instead. Keyed on the length rather than the text: the
    // last decimals change every frame and the width does not, this being monospaced.
    var scale by remember(formatted.length, currency, negative) { mutableFloatStateOf(1f) }
    val fitted = style.copy(
        fontSize = style.fontSize * scale,
        lineHeight = style.lineHeight * scale,
    )

    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = colour, fontSize = fitted.fontSize * 0.55f)) {
            if (negative) append("-")
            append(currencySymbol(currency))
            append(" ")
        }
        withStyle(SpanStyle(color = colour)) {
            append(formatted.substring(0, steadyEnd.coerceAtMost(formatted.length)))
        }
        if (steadyEnd < formatted.length) {
            withStyle(SpanStyle(color = colour.copy(alpha = 0.45f))) {
                append(formatted.substring(steadyEnd))
            }
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        Text(
            text = text,
            style = fitted,
            maxLines = 1,
            softWrap = false,
            onTextLayout = { result ->
                if (result.hasVisualOverflow && scale > 0.5f) scale *= 0.92f
            },
        )
    }
}
