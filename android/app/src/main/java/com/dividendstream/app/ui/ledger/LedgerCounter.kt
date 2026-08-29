package com.dividendstream.app.ui.ledger

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
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

/**
 * The month's surplus as a fund sees it: the flows ticking, plus what was written down.
 *
 * The server hands a fund `accruedThisMonth` as its share of `netAccrued`, and `netAccrued`
 * is the flows accrued *plus* the month's records. The client redraws that share every frame
 * so a fund cannot lag the counter above it -- but [rememberNetAccrued] ticks the flows alone,
 * because that is all the flows know how to do. Multiplying only that by the share left every
 * fund short by its cut of everything the person had written down: a month with a RM153 side
 * job in it showed the funds a month without one, and the RM153 would then appear all at once
 * on the first of the next month, when the past-month walk picked it up.
 *
 * So the fixed part is added here, in one place, rather than at each of the three call sites
 * that need it.
 *
 * [recordedThisMonth] is the *month's* records even when the screen is showing a day: a fund's
 * share is a share of a month, and switching to today must not make a fund appear to shrink.
 */
@Composable
fun rememberFundSurplus(
    streams: LedgerStreams,
    recordedThisMonth: BigDecimal,
    clock: ServerClock,
): State<BigDecimal> {
    val amount = remember { mutableStateOf(BigDecimal.ZERO) }

    LaunchedEffect(streams, recordedThisMonth, clock) {
        fun surplus(): BigDecimal =
            AccumulationCalculator.totalAccruedAt(streams.income, clock.now())
                .subtract(AccumulationCalculator.totalAccruedAt(streams.expense, clock.now()))
                .add(recordedThisMonth)

        amount.value = surplus()
        while (true) {
            withFrameNanos { }
            amount.value = surplus()
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

    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = colour, fontSize = style.fontSize * 0.55f)) {
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
        Text(text = text, style = style, maxLines = 1)
    }
}
