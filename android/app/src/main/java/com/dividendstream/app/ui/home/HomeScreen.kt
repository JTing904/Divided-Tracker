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
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.StaleDataBanner
import com.dividendstream.app.ui.components.StatTile
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
import java.time.LocalTime
import java.time.ZoneId

/**
 * Everything at once: dividends and the ledger, on one screen.
 *
 * The headline is a **rate**, not a total, and that is the whole design of this screen rather
 * than a stylistic choice. A dividend accrues across its own cycle; the ledger accrues across a
 * calendar month. Adding those two running figures would produce a number measured over two
 * different windows at the same time — true of neither, and impossible to explain. Rates carry
 * no window at all: ringgit per second plus ringgit per second is ringgit per second.
 *
 * The two running totals still appear, in their own cards, each labelled with the window it is
 * measured over. Whoever reads a figure here can always tell what it counts.
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

            item {
                TotalCard(
                    snapshot = snapshot,
                    ledger = ledger,
                    dividendStreams = state.streams,
                    ledgerStreams = state.ledgerStreams,
                    clock = viewModel.serverClock,
                )
            }

            item { CombinedRateCard(snapshot, ledger) }

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
    ledgerStreams: LedgerStreams,
    clock: ServerClock,
) {
    val currency = ledger?.currency ?: snapshot.currency
    val dividends by rememberAccruedAmount(dividendStreams, clock)
    val thisMonth by rememberNetAccrued(ledgerStreams, clock)

    // The settled months plus this one, recomputed here each frame so the total keeps pace
    // with the ledger screen instead of standing still until the next refresh.
    val kept = (ledger?.keptBeforeThisMonth ?: BigDecimal.ZERO)
        .add(thisMonth)
        .add(ledger?.recordedNet ?: BigDecimal.ZERO)
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

                Component("Kept", kept.formatAmount(Precision.AMOUNT), "income less what you spent")
                Component(
                    "Dividends",
                    dividends.formatAmount(Precision.AMOUNT),
                    "accruing, not yet paid",
                )

                Spacer(Modifier.height(10.dp))
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

/**
 * The one figure that legitimately combines the two halves of the app.
 *
 * Zero on both sides is not hidden: "nothing is coming in yet" is a true and useful thing for
 * a new account to be told, and an empty space would leave somebody wondering whether the
 * screen had failed to load.
 */
@Composable
private fun CombinedRateCard(snapshot: LiveDividendDto, ledger: LedgerDto?) {
    val perSecond = snapshot.rate.perSecond.add(ledger?.netRatePerSecond ?: BigDecimal.ZERO)
    val perDay = snapshot.rate.perDay.add(ledger?.rate?.perDay ?: BigDecimal.ZERO)
    val perMonth = snapshot.rate.perMonth.add(ledger?.rate?.perMonth ?: BigDecimal.ZERO)
    val perYear = snapshot.rate.perYear.add(ledger?.rate?.perYear ?: BigDecimal.ZERO)
    val negative = perSecond.signum() < 0
    val currency = snapshot.currency

    DsCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), border = null) {
        Box(
            modifier = Modifier.fillMaxWidth().background(
                Brush.horizontalGradient(
                    listOf(
                        if (negative) DividendColors.Danger.copy(alpha = 0.10f) else DividendColors.GrowthGlow,
                        Color.Transparent,
                    ),
                ),
            ),
        ) {
            Column {
                OverlineText(
                    when {
                        perSecond.signum() == 0 -> "Nothing coming in yet"
                        negative -> "Going out, every second"
                        else -> "Coming in, every second"
                    },
                )
                Spacer(Modifier.height(10.dp))
                SignedLiveAmountText(
                    amount = perSecond,
                    currency = currency,
                    decimals = Precision.RATE,
                    steadyDecimals = 8,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Per day", perDay.formatMoney(currency), Modifier.weight(1f))
                    StatTile("Per month", perMonth.formatMoney(currency), Modifier.weight(1f))
                    StatTile("Per year", perYear.formatMoney(currency), Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Dividends and your ledger added together. Only the pace is combined — the " +
                        "two running totals below are measured over different periods.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
