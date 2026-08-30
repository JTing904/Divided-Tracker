package com.dividendstream.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.Precision
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.core.formatAmount
import com.dividendstream.app.core.formatFull
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.data.remote.LiveDividendDto
import com.dividendstream.app.ui.components.AccrualProgressBar
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.StaleDataBanner
import com.dividendstream.app.ui.components.UpdateAvailableBanner
import com.dividendstream.app.ui.components.describeAge
import com.dividendstream.app.ui.components.rememberAccruedAmount
import com.dividendstream.app.ui.dashboard.DashboardViewModel
import com.dividendstream.app.ui.ledger.LedgerStreams
import com.dividendstream.app.ui.ledger.SignedLiveAmountText
import com.dividendstream.app.ui.ledger.rememberNetAccrued
import com.dividendstream.app.ui.theme.DividendColors
import com.dividendstream.app.ui.theme.MonoFigure
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Everything at once: dividends and the ledger, on one screen.
 *
 * It opens with today, because that is the question the app is asked several times a day and
 * standing at a counter is where it gets asked. It used to open with a total and a per-second
 * rate — a screen and a half of figures nobody consults hourly, in front of everything that
 * could be acted on. Both are still here; they are simply no longer first.
 *
 * A rate is the one thing that may combine the two halves of the app. A dividend accrues across
 * its own cycle and the ledger across a calendar month, so adding the two running totals would
 * give a number measured over two windows at once — true of neither. Ringgit per second plus
 * ringgit per second is ringgit per second, so the pace is combined and the totals are not.
 */
@Composable
fun HomeScreen(
    viewModel: DashboardViewModel,
    userName: String,
    onOpenDividends: () -> Unit,
    onOpenLedger: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val ledger = state.ledger

    when {
        state.isLoading && snapshot == null -> LoadingBox(modifier.fillMaxSize())

        snapshot == null -> Column(modifier.fillMaxSize().padding(20.dp)) {
            state.error?.let { ErrorBanner(error = it, onRetry = viewModel::refresh) }
        }

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Greeting(userName) }

            state.newerRelease?.let { version ->
                item {
                    UpdateAvailableBanner(version = version, onDismiss = viewModel::dismissUpdateNotice)
                }
            }

            if (state.isStale) {
                item {
                    StaleDataBanner(
                        cachedAgo = state.cachedAt.describeAge(viewModel.serverClock),
                        reason = state.staleError,
                    )
                }
            }

            ledger?.let { loaded ->
                item {
                    TodayCard(
                        ledger = loaded,
                        clock = viewModel.serverClock,
                        onClick = onOpenLedger,
                    )
                }
            }

            item {
                TotalCard(
                    snapshot = snapshot,
                    ledger = ledger,
                    dividendStreams = state.streams,
                    clock = viewModel.serverClock,
                )
            }

            item {
                DividendCard(
                    snapshot = snapshot,
                    streams = state.streams,
                    clock = viewModel.serverClock,
                    onClick = onOpenDividends,
                )
            }

            item {
                LedgerCard(
                    ledger = ledger,
                    streams = state.ledgerStreams,
                    clock = viewModel.serverClock,
                    onClick = onOpenLedger,
                )
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Greeting(userName: String) {
    val hour = LocalTime.now(ZoneId.systemDefault()).hour
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    Text(
        "$greeting, ${userName.substringBefore(' ')}",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

/**
 * What is left to spend today.
 *
 * The allowance is the ledger's own net per day: what the declared income leaves after the
 * declared outgoings, spread evenly. Anything written down today moves it, because moving this
 * is the point of writing it down.
 *
 * Dividends are deliberately left out. They have not been paid, so they are not money anybody
 * can spend today — the same reason the funds hold only what has arrived.
 */
@Composable
private fun TodayCard(ledger: LedgerDto, clock: ServerClock, onClick: () -> Unit) {
    val today = LocalDate.ofInstant(clock.now(), ZoneId.systemDefault())
    val todays = ledger.entries.filter { it.occurredOn == today }
    val spent = todays.filter { it.direction == "EXPENSE" }
        .fold(BigDecimal.ZERO) { sum, entry -> sum.add(entry.amount) }
    val earned = todays.filter { it.direction != "EXPENSE" }
        .fold(BigDecimal.ZERO) { sum, entry -> sum.add(entry.amount) }

    val allowance = ledger.rate.perDay.add(earned)
    // Nothing declared and nothing written down: there is no day to report on, and a card
    // reading "RM0.00 left" would look like an answer rather than like an empty ledger.
    if (allowance.signum() <= 0 && spent.signum() == 0) return

    val left = allowance.subtract(spent)
    val over = left.signum() < 0
    val used = if (allowance.signum() <= 0) 1f
    else spent.toFloat() / allowance.toFloat()

    DsCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        contentPadding = PaddingValues(20.dp),
        border = null,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().background(
                Brush.horizontalGradient(
                    listOf(
                        if (over) DividendColors.Danger.copy(alpha = 0.10f) else DividendColors.GrowthGlow,
                        Color.Transparent,
                    ),
                ),
            ),
        ) {
            Column {
                OverlineText(if (over) "Over today" else "Left to spend today")
                Spacer(Modifier.height(10.dp))
                SignedLiveAmountText(
                    amount = left,
                    currency = ledger.currency,
                    decimals = Precision.AMOUNT,
                )
                Spacer(Modifier.height(12.dp))
                AccrualProgressBar(progress = used)
                Spacer(Modifier.height(10.dp))
                Text(
                    "${spent.formatMoney(ledger.currency)} spent of " +
                        "${allowance.formatMoney(ledger.currency)} for today" +
                        if (earned.signum() > 0) {
                            ", ${earned.formatMoney(ledger.currency)} of it recorded in"
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What you have, moving.
 *
 * Two things go in, and neither is a share price. The market value of a portfolio belongs to
 * the portfolio, and adding it here would put a real quoted number beside two worked-out ones
 * and call the sum a single figure.
 *
 * - **Kept** is what the declared income has left over after the declared outgoings and
 *   everything written down, added up across every month.
 * - **Dividends** is what is accruing towards payments that have not happened yet.
 *
 * Both move every frame, which is the point: a home screen leading with a rate gives nothing
 * to watch, and the rate is what the card below this one is for.
 */
@Composable
private fun TotalCard(
    snapshot: LiveDividendDto,
    ledger: LedgerDto?,
    dividendStreams: List<com.dividendstream.app.domain.AccumulationStream>,
    clock: ServerClock,
) {
    val currency = ledger?.currency ?: snapshot.currency
    val dividends by rememberAccruedAmount(dividendStreams, clock)

    val perDay = snapshot.rate.perDay.add(ledger?.rate?.perDay ?: BigDecimal.ZERO)
    val perMonth = snapshot.rate.perMonth.add(ledger?.rate?.perMonth ?: BigDecimal.ZERO)
    val perYear = snapshot.rate.perYear.add(ledger?.rate?.perYear ?: BigDecimal.ZERO)

    // The same sum the ledger screen shows, field for field. It used to be worked out here a
    // second way -- the settled months plus this one, ticking -- and the two screens then
    // disagreed about what a person had, which is the one thing a total must not do.
    //
    // Money that has arrived only: what was put into funds by hand, every month that has
    // finished, and what this month has paid so far. A settled month is counted once, in
    // keptBeforeThisMonth, and taken back out of the funds' side.
    val byHand = ledger?.funds.orEmpty().fold(BigDecimal.ZERO) { sum, fund ->
        sum.add(fund.carriedOver).subtract(fund.earmarkedEarlier)
    }
    val kept = byHand
        .add(ledger?.keptBeforeThisMonth ?: BigDecimal.ZERO)
        .add(ledger?.monthReceivedNet ?: BigDecimal.ZERO)
    val total = kept.add(dividends)

    DsCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), border = null) {
        Box(
            modifier = Modifier.fillMaxWidth().background(
                Brush.horizontalGradient(
                    listOf(
                        if (total.signum() < 0) DividendColors.Danger.copy(alpha = 0.10f)
                        else DividendColors.GrowthGlow,
                        Color.Transparent,
                    ),
                ),
            ),
        ) {
            Column {
                OverlineText("What you have")
                Spacer(Modifier.height(10.dp))
                SignedLiveAmountText(amount = total, currency = currency)
                Spacer(Modifier.height(16.dp))

                Component("Kept", kept.formatAmount(Precision.AMOUNT), "money that has arrived")
                Component(
                    "Dividends",
                    dividends.formatAmount(Precision.AMOUNT),
                    "accruing, not yet paid",
                )

                Spacer(Modifier.height(10.dp))
                // The pace, as three figures on a line of prose. It had a card of its own, with
                // the per-second reading to eight decimals and three tiles too narrow to hold a
                // year's worth of ringgit -- a screenful spent on something nobody acts on, and
                // the Dividends tab breaks the same rate down properly for anyone who wants it.
                Text(
                    if (perDay.signum() == 0) {
                        "Nothing coming in yet."
                    } else {
                        val verb = if (perDay.signum() < 0) "Going out" else "Coming in"
                        "$verb at ${perDay.formatMoney(currency)} a day, " +
                            "${perMonth.formatMoney(currency)} a month, " +
                            "${perYear.formatMoney(currency)} a year."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Worked out from what you have told the app and what your holdings are " +
                        "expected to pay. An estimate of where you stand, not a bank balance.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Component(label: String, value: String, note: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(value, style = MonoFigure, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
private fun DividendCard(
    snapshot: LiveDividendDto,
    streams: List<com.dividendstream.app.domain.AccumulationStream>,
    clock: ServerClock,
    onClick: () -> Unit,
) {
    val accrued by rememberAccruedAmount(streams, clock)

    SummaryCard(
        emoji = "📈",
        tint = DividendColors.Growth,
        title = "Dividends",
        onClick = onClick,
    ) {
        Text(
            accrued.formatAmount(Precision.LIVE),
            style = MonoFigure,
            color = DividendColors.Growth,
            maxLines = 1,
        )
        OverlineText("accruing towards ${snapshot.totalExpected.formatMoney(snapshot.currency)}")
        snapshot.nextPayment?.let { next ->
            Spacer(Modifier.height(8.dp))
            Text(
                "Next: ${next.symbol} ${next.expectedAmount.formatMoney(next.currency)} " +
                    "on ${next.paymentDate.formatFull()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LedgerCard(
    ledger: LedgerDto?,
    streams: LedgerStreams,
    clock: ServerClock,
    onClick: () -> Unit,
) {
    val net by rememberNetAccrued(streams, clock)

    SummaryCard(
        emoji = "🧾",
        tint = DividendColors.Growth,
        title = "Ledger",
        onClick = onClick,
    ) {
        if (ledger == null) {
            Text(
                "Nothing recorded yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SignedLiveAmountText(
                amount = net,
                currency = ledger.currency,
                decimals = Precision.LIVE,
                style = MonoFigure,
            )
            OverlineText(if (net.signum() < 0) "short this month" else "left over this month")
        if (ledger.keptSoFar.signum() != 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "${ledger.keptSoFar.formatMoney(ledger.currency)} kept in total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
            if (ledger.totalFundBalance.signum() > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "${ledger.totalFundBalance.formatMoney(ledger.currency)} banked across your funds",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    emoji: String,
    tint: Color,
    title: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    DsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    emoji,
                    fontSize = with(LocalDensity.current) { 18.dp.toSp() },
                    lineHeight = with(LocalDensity.current) { 18.dp.toSp() },
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Column { content() }
    }
}
