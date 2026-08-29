package com.dividendstream.app.ui.dashboard

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.Precision
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.core.formatCountdown
import com.dividendstream.app.core.formatFull
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.core.formatShares
import com.dividendstream.app.data.remote.DividendDto
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.data.remote.LiveDividendDto
import com.dividendstream.app.data.remote.LiveStreamDto
import com.dividendstream.app.data.remote.toAccumulationStream
import com.dividendstream.app.ui.components.AccrualProgressBar
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.describeAge
import com.dividendstream.app.ui.components.EmptyState
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LiveAmountText
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.PrimaryButton
import com.dividendstream.app.ui.components.SectionHeader
import com.dividendstream.app.ui.components.StaleDataBanner
import com.dividendstream.app.ui.components.StatTile
import com.dividendstream.app.ui.components.StatusPill
import com.dividendstream.app.ui.components.UpdateAvailableBanner
import com.dividendstream.app.ui.components.rememberAccruedAmount
import com.dividendstream.app.ui.components.rememberSecondTicker
import com.dividendstream.app.ui.theme.DividendColors
import com.dividendstream.app.ui.theme.MonoFigure
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    userName: String,
    onAddStock: () -> Unit,
    onOpenStock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot

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
            item { GreetingHeader(userName, snapshot) }

            state.newerRelease?.let { version ->
                item {
                    UpdateAvailableBanner(
                        version = version,
                        onDismiss = viewModel::dismissUpdateNotice,
                    )
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

            item { LiveDividendCard(state, viewModel.serverClock) }

            item { RateBreakdownGrid(snapshot) }

            state.ledger?.takeIf { it.netRatePerSecond.signum() != 0 }?.let { ledger ->
                item { CombinedPaceCard(snapshot, ledger) }
            }

            snapshot.nextPayment?.let { next ->
                item {
                    Column {
                        SectionHeader("Next dividend")
                        Spacer(Modifier.height(10.dp))
                        NextPaymentCard(next, viewModel.serverClock)
                    }
                }
            }

            item { PortfolioTotals(snapshot) }

            if (state.isEmpty) {
                item {
                    EmptyState(
                        title = "No dividend stocks yet",
                        message = "Add a holding and watch its expected dividend start accumulating.",
                        action = { PrimaryButton("Add your first stock", onAddStock) },
                    )
                }
            } else {
                item { SectionHeader("Your streams") }
                items(snapshot.streams, key = { it.transactionId }) { stream ->
                    StreamRow(
                        stream = stream,
                        clock = viewModel.serverClock,
                        onClick = { onOpenStock(stream.symbol) },
                    )
                }
            }

            item { EstimateDisclaimer() }
        }
    }
}

@Composable
private fun GreetingHeader(userName: String, snapshot: LiveDividendDto) {
    val greeting = when (LocalTime.now(ZoneId.systemDefault()).hour) {
        in 0..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }

    Column {
        Text(
            text = if (userName.isBlank()) greeting else "$greeting, ${userName.substringBefore(' ')}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverlineText("Total received")
            Spacer(Modifier.width(10.dp))
            Text(
                snapshot.totalReceived.formatMoney(snapshot.currency),
                style = MonoFigure,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}

/** The centrepiece: the number the whole product exists to show. */
@Composable
private fun LiveDividendCard(state: DashboardUiState, clock: ServerClock) {
    val snapshot = state.snapshot ?: return
    val accrued by rememberAccruedAmount(state.streams, clock)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    listOf(DividendColors.GrowthGlow, MaterialTheme.colorScheme.surface),
                ),
                shape = RoundedCornerShape(22.dp),
            ),
    ) {
        DsCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 24.dp, horizontal = 20.dp),
            border = null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(DividendColors.Growth, CircleShape))
                Spacer(Modifier.width(8.dp))
                OverlineText("Live dividend", color = DividendColors.Growth)
            }

            Spacer(Modifier.height(14.dp))

            LiveAmountText(
                amount = accrued,
                currency = snapshot.currency,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = "Rate: ${snapshot.rate.perSecond.formatMoney(snapshot.currency, Precision.RATE)} / sec",
                style = MonoFigure,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = "of ${snapshot.totalExpected.formatMoney(snapshot.currency)} expected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Dividends and the ledger, added together.
 *
 * Only the *rate* is combined, never the accrued totals. A dividend accrues over its own cycle
 * and the ledger over a calendar month, so adding the two running figures would produce a
 * number measured over two different windows at once -- true of neither. Rates have no window:
 * ringgit per second plus ringgit per second is ringgit per second, and it is the more striking
 * figure anyway.
 */
@Composable
private fun CombinedPaceCard(snapshot: LiveDividendDto, ledger: LedgerDto) {
    val currency = snapshot.currency
    val perSecond = snapshot.rate.perSecond.add(ledger.netRatePerSecond)
    val perDay = snapshot.rate.perDay.add(ledger.rate.perDay)
    val perMonth = snapshot.rate.perMonth.add(ledger.rate.perMonth)
    val negative = perSecond.signum() < 0

    DsCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlineText("Everything together")
            Text(
                perSecond.formatMoney(currency, Precision.RATE) + " /sec",
                style = MonoFigure,
                color = if (negative) DividendColors.Danger else MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Per day", perDay.formatMoney(currency), Modifier.weight(1f))
            StatTile("Per month", perMonth.formatMoney(currency), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Dividends plus what your ledger says is left over. The two are counted separately " +
                "on their own screens.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RateBreakdownGrid(snapshot: LiveDividendDto) {
    val currency = snapshot.currency
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Per sec", snapshot.rate.perSecond.formatMoney(currency, Precision.RATE), Modifier.weight(1f))
            StatTile("Per min", snapshot.rate.perMinute.formatMoney(currency, 6), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Per hour", snapshot.rate.perHour.formatMoney(currency, 4), Modifier.weight(1f))
            StatTile("Per day", snapshot.rate.perDay.formatMoney(currency), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Per month", snapshot.rate.perMonth.formatMoney(currency), Modifier.weight(1f))
            StatTile("Per year", snapshot.rate.perYear.formatMoney(currency), Modifier.weight(1f))
        }
    }
}

@Composable
private fun NextPaymentCard(payment: DividendDto, clock: ServerClock) {
    // Once a second, not once a frame: a countdown does not need 60fps.
    val now by rememberSecondTicker(clock)
    val remaining = Duration.between(now, payment.accumulationEnd).seconds

    DsCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    payment.companyName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    payment.paymentDate.formatFull(),
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    payment.expectedAmount.formatMoney(payment.currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    formatCountdown(remaining),
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PortfolioTotals(snapshot: LiveDividendDto) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatTile(
            label = "Total expected",
            value = snapshot.totalExpected.formatMoney(snapshot.currency),
            modifier = Modifier.weight(1f),
            valueColor = MaterialTheme.colorScheme.primary,
        )
        StatTile(
            label = "Dividend stocks",
            value = snapshot.activeStockCount.toString(),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StreamRow(stream: LiveStreamDto, clock: ServerClock, onClick: () -> Unit) {
    val streams = remember(stream.transactionId, stream.ratePerSecond) {
        listOf(stream.toAccumulationStream())
    }
    val accrued by rememberAccruedAmount(streams, clock)

    DsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stream.companyName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${stream.symbol} · ${stream.shares.formatShares()} shares",
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(stream.status)
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                OverlineText("Accumulated")
                Spacer(Modifier.height(4.dp))
                LiveAmountText(
                    amount = accrued,
                    currency = stream.currency,
                    style = MonoFigure.copy(fontSize = 17.sp),
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                OverlineText("Expected")
                Spacer(Modifier.height(4.dp))
                Text(
                    stream.expectedAmount.formatMoney(stream.currency),
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        AccrualProgressBar(progress = stream.progress.toFloat())
    }
}

@Composable
private fun EstimateDisclaimer() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 24.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Default.TrendingUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Estimated accumulation towards your expected dividend. This is not money " +
                "received, and dividends are not guaranteed until they are paid.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

