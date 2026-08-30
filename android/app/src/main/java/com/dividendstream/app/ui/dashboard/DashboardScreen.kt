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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import com.dividendstream.app.core.formatAmount
import com.dividendstream.app.ui.components.DsTextField
import com.dividendstream.app.ui.components.FittedFigure
import java.math.BigDecimal

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

            item { IncomeGoalCard(state, snapshot, viewModel.serverClock, viewModel::setIncomeGoal) }

            item { RateBreakdownGrid(snapshot) }

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

/**
 * How far a month's dividends go towards what somebody is aiming for.
 *
 * The one figure on this screen that is nobody's business but the person's, which is why it
 * is kept with their own data rather than behind the price server -- and why it survives the
 * server being asleep, unlike everything else here.
 *
 * The bar is the projection against the goal, because that is the question ("will I get
 * there?"). The figure beside it is what has actually accrued, and it moves, because that is
 * the answer arriving. A tracker that only draws a static bar can tell you where you stand; it
 * cannot show you getting closer.
 */
@Composable
private fun IncomeGoalCard(
    state: DashboardUiState,
    snapshot: LiveDividendDto,
    clock: ServerClock,
    onSet: (BigDecimal?) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val goal = state.incomeGoal
    val perMonth = snapshot.rate.perMonth
    val accrued by rememberAccruedAmount(state.streams, clock)

    DsCard(modifier = Modifier.fillMaxWidth().clickable { editing = true }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OverlineText(
                if (goal == null) "Set a monthly income goal" else "Monthly income goal",
                modifier = Modifier.weight(1f),
            )
            OverlineText(
                if (goal == null) "Set" else "Change",
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                softWrap = false,
            )
        }

        if (goal == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Say what you want your holdings to pay each month, and this shows how close " +
                    "they are -- and how much closer they get every second.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val reached = if (goal.signum() <= 0) 0f
            else perMonth.toFloat() / goal.toFloat()

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                FittedFigure(
                    text = perMonth.formatMoney(snapshot.currency),
                    style = MonoFigure.copy(fontSize = MonoFigure.fontSize * 1.6f),
                    color = if (reached >= 1f) DividendColors.Growth
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "of ${goal.formatMoney(snapshot.currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            AccrualProgressBar(progress = reached)
            Spacer(Modifier.height(8.dp))
            Text(
                if (reached >= 1f) {
                    "You are there -- ${perMonth.formatMoney(snapshot.currency)} a month expected."
                } else {
                    "${goal.subtract(perMonth).formatMoney(snapshot.currency)} a month to go."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    accrued.formatAmount(Precision.LIVE),
                    style = MonoFigure,
                    color = DividendColors.Growth,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                OverlineText("accruing right now")
            }
        }
    }

    if (editing) {
        IncomeGoalDialog(
            current = goal,
            onDismiss = { editing = false },
            onSet = { editing = false; onSet(it) },
        )
    }
}

@Composable
private fun IncomeGoalDialog(
    current: BigDecimal?,
    onDismiss: () -> Unit,
    onSet: (BigDecimal?) -> Unit,
) {
    var text by remember { mutableStateOf(current?.toPlainString().orEmpty()) }
    val parsed = text.trim().replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                "Monthly income goal",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    "What you would like your holdings to pay you every month.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                DsTextField(
                    label = "Each month",
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "500.00",
                    keyboardType = KeyboardType.Decimal,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSet) }, enabled = parsed != null) {
                Text(
                    "Save",
                    color = if (parsed != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        dismissButton = {
            Row {
                // Clearing is a real choice, not an edge case: a goal somebody has outgrown
                // should be removable without setting a fake one.
                if (current != null) {
                    TextButton(onClick = { onSet(null) }) {
                        Text("Clear", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}
