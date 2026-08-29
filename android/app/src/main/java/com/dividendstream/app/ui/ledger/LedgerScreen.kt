package com.dividendstream.app.ui.ledger

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.Precision
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.core.formatAmount
import com.dividendstream.app.core.formatDayMonth
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.core.formatPercent
import com.dividendstream.app.data.remote.CashFlowDto
import com.dividendstream.app.data.remote.FundDto
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.data.remote.LedgerEntryDto
import com.dividendstream.app.data.remote.MonthlyLedgerTotalDto
import com.dividendstream.app.data.remote.toAccumulationStream
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.EmptyState
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.PrimaryButton
import com.dividendstream.app.ui.components.SectionHeader
import com.dividendstream.app.ui.components.StaleDataBanner
import com.dividendstream.app.ui.components.StatTile
import com.dividendstream.app.ui.components.describeAge
import com.dividendstream.app.ui.theme.DividendColors
import com.dividendstream.app.ui.theme.MonoFigure
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The ledger.
 *
 * The screen is organised around one distinction, and everything on it is placed to keep that
 * distinction visible: the top half is a *projection* -- what the person told the app they earn
 * and spend, ticking forward second by second -- and the bottom half is a *record* of what they
 * actually wrote down. They are never summed, and the headings say which is which.
 */
@Composable
fun LedgerScreen(
    viewModel: LedgerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ledger = state.ledger
    var editor by remember { mutableStateOf<LedgerEditor?>(null) }

    when {
        state.isLoading && ledger == null -> LoadingBox(modifier.fillMaxSize())

        ledger == null -> Column(modifier.fillMaxSize().padding(20.dp)) {
            state.error?.let { ErrorBanner(error = it, onRetry = viewModel::refresh) }
        }

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { MonthHeader(ledger) }

            if (state.isStale) {
                item {
                    StaleDataBanner(
                        cachedAgo = state.cachedAt.describeAge(viewModel.serverClock),
                        reason = state.staleError,
                    )
                }
            }

            state.actionError?.let { error ->
                item { ErrorBanner(error = error, onRetry = viewModel::dismissActionError) }
            }

            item { NetCounterCard(state, viewModel.serverClock) }

            item { InOutRow(state, viewModel.serverClock) }

            if (state.hasNothing) {
                item {
                    EmptyState(
                        title = "Nothing to count yet",
                        message = "Tell the app what you earn -- by the day, the week, the month " +
                            "or the year -- and watch it add up second by second.",
                        action = {
                            PrimaryButton(
                                text = "Add income",
                                onClick = { editor = LedgerEditor.Flow("INCOME") },
                            )
                        },
                    )
                }
            }

            item {
                SectionHeader("What repeats") {
                    Row {
                        AddChip("In", DividendColors.Growth) { editor = LedgerEditor.Flow("INCOME") }
                        Spacer(Modifier.width(8.dp))
                        AddChip("Out", DividendColors.Danger) { editor = LedgerEditor.Flow("EXPENSE") }
                    }
                }
            }

            if (ledger.flows.isEmpty()) {
                item { HintCard("Add a salary, an allowance, rent or a subscription. Everything else on this screen is built from these.") }
            } else {
                items(ledger.flows, key = { it.id }) { flow ->
                    CashFlowRow(
                        flow = flow,
                        currency = ledger.currency,
                        clock = viewModel.serverClock,
                        onEdit = { editor = LedgerEditor.Flow(flow.direction, flow) },
                        onDelete = { viewModel.deleteFlow(flow.id) },
                    )
                }
            }

            item {
                SectionHeader("Where it goes") {
                    AddChip("Fund", DividendColors.Growth) { editor = LedgerEditor.Fund() }
                }
            }

            item { AllocationBar(ledger) }

            if (ledger.funds.isEmpty()) {
                item { HintCard("Split what is left into funds -- an emergency pot, a trip, an investment -- by percentage. Each one fills in real time.") }
            } else {
                items(ledger.funds, key = { it.id }) { fund ->
                    FundRow(
                        fund = fund,
                        ledger = ledger,
                        streams = state.streams,
                        clock = viewModel.serverClock,
                        onEdit = { editor = LedgerEditor.Fund(fund) },
                        onDelete = { viewModel.deleteFund(fund.id) },
                    )
                }
            }

            item {
                SectionHeader("What actually happened") {
                    AddChip("Record", MaterialTheme.colorScheme.primary) {
                        editor = LedgerEditor.Entry("EXPENSE")
                    }
                }
            }

            item { ActualTotals(ledger) }

            if (ledger.entries.isEmpty()) {
                item { HintCard("Nothing written down this month. Records are facts; they are kept apart from the projection above and never added to it.") }
            } else {
                items(ledger.entries, key = { it.id }) { entry ->
                    EntryRow(
                        entry = entry,
                        currency = ledger.currency,
                        onDelete = { viewModel.deleteEntry(entry.id) },
                    )
                }
            }

            val past = ledger.months.filter { it.entryCount > 0 }
            if (past.isNotEmpty()) {
                item { SectionHeader("Earlier months") }
                items(past, key = { it.month }) { month -> MonthRow(month, ledger.currency) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editor?.let { open ->
        LedgerEditorDialog(
            editor = open,
            viewModel = viewModel,
            isSaving = state.isSaving,
            onDismiss = { editor = null },
        )
    }
}

// --- the counter -------------------------------------------------------------

@Composable
private fun MonthHeader(ledger: LedgerDto) {
    Column {
        Text(
            ledger.month.toDisplayMonth(),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            when (ledger.daysLeftInMonth) {
                1L -> "Resets tomorrow"
                else -> "Resets in ${ledger.daysLeftInMonth} days"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NetCounterCard(state: LedgerUiState, clock: ServerClock) {
    val ledger = state.ledger ?: return
    val net by rememberNetAccrued(state.streams, clock)
    val negative = net.signum() < 0

    DsCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        border = null,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            if (negative) DividendColors.Danger.copy(alpha = 0.10f)
                            else DividendColors.GrowthGlow,
                            androidx.compose.ui.graphics.Color.Transparent,
                        ),
                    ),
                ),
        ) {
            Column {
                OverlineText(if (negative) "Short this month" else "Left over this month")
                Spacer(Modifier.height(10.dp))
                SignedLiveAmountText(amount = net, currency = ledger.currency)
                Spacer(Modifier.height(12.dp))
                Text(
                    ledger.netRatePerSecond.paceSentence(ledger),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The pace, in the words a person would use. Deliberately not four decimals of a per-second
 * rate: nobody budgets by the second, and the big figure above is already doing the moving.
 */
private fun BigDecimal.paceSentence(ledger: LedgerDto): String {
    if (signum() == 0) return "Nothing repeating yet"
    val perDay = ledger.rate.perDay
    val verb = if (signum() < 0) "Losing" else "Saving"
    return "$verb ${perDay.abs().formatMoney(ledger.currency)} a day " +
        "· ${ledger.rate.perMonth.abs().formatMoney(ledger.currency)} a month"
}

@Composable
private fun InOutRow(state: LedgerUiState, clock: ServerClock) {
    val ledger = state.ledger ?: return
    val income by rememberSideAccrued(state.streams.income, clock)
    val expense by rememberSideAccrued(state.streams.expense, clock)

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            label = "In so far",
            value = income.formatAmount(Precision.AMOUNT),
            modifier = Modifier.weight(1f),
            valueColor = DividendColors.Growth,
        )
        StatTile(
            label = "Out so far",
            value = expense.formatAmount(Precision.AMOUNT),
            modifier = Modifier.weight(1f),
            valueColor = DividendColors.Danger,
        )
    }
}

// --- recurring flows ---------------------------------------------------------

@Composable
private fun CashFlowRow(
    flow: CashFlowDto,
    currency: String,
    clock: ServerClock,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val income = flow.direction == "INCOME"
    val badge = LedgerIcon.of(flow.category)
    val stream = remember(flow) { listOfNotNull(flow.toAccumulationStream()) }
    val accrued by rememberSideAccrued(stream, clock)

    DsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(badge)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    flow.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    "${flow.amount.formatMoney(currency)} ${flow.period.perPhrase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (income) "+" else "-") + accrued.formatAmount(Precision.AMOUNT),
                    style = MonoFigure,
                    color = if (income) DividendColors.Growth else DividendColors.Danger,
                    maxLines = 1,
                )
                OverlineText("this month")
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${flow.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        if (flow.windowStart == null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Not running this month",
                style = MaterialTheme.typography.bodySmall,
                color = DividendColors.Warning,
            )
        }
    }
}

// --- funds -------------------------------------------------------------------

@Composable
private fun AllocationBar(ledger: LedgerDto) {
    val allocated = ledger.allocatedPercent.toFloat().coerceIn(0f, 100f) / 100f

    DsCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OverlineText("Allocated")
            Text(
                ledger.allocatedPercent.formatPercent(0),
                style = MonoFigure,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { allocated },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (ledger.unallocatedPercent.signum() > 0) {
                "${ledger.unallocatedPercent.formatPercent(0)} of what is left is unassigned"
            } else {
                "Every ringgit left over has somewhere to go"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FundRow(
    fund: FundDto,
    ledger: LedgerDto,
    streams: LedgerStreams,
    clock: ServerClock,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val badge = LedgerIcon.of(fund.icon)
    val net by rememberNetAccrued(streams, clock)
    // A fund takes its share of the surplus, and there is no share of a deficit.
    val share = remember(fund) { fund.percent.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP) }
    val filled = if (net.signum() <= 0) BigDecimal.ZERO else net.multiply(share)
    val target = fund.plannedThisMonth
    val progress =
        if (target.signum() <= 0) 0f
        else filled.divide(target, 6, RoundingMode.DOWN).toFloat().coerceIn(0f, 1f)

    DsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(badge)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    fund.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    "${fund.percent.formatPercent(0)} of what is left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    filled.formatAmount(Precision.AMOUNT),
                    style = MonoFigure,
                    color = badge.tint,
                    maxLines = 1,
                )
                OverlineText("of ${target.formatMoney(ledger.currency)}")
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove ${fund.name}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = badge.tint,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

// --- records -----------------------------------------------------------------

@Composable
private fun ActualTotals(ledger: LedgerDto) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            label = "Recorded in",
            value = ledger.actualIncome.formatAmount(Precision.AMOUNT),
            modifier = Modifier.weight(1f),
            valueColor = DividendColors.Growth,
        )
        StatTile(
            label = "Recorded out",
            value = ledger.actualExpense.formatAmount(Precision.AMOUNT),
            modifier = Modifier.weight(1f),
            valueColor = DividendColors.Danger,
        )
    }
}

@Composable
private fun EntryRow(entry: LedgerEntryDto, currency: String, onDelete: () -> Unit) {
    val income = entry.direction == "INCOME"
    val badge = LedgerIcon.of(entry.category)

    DsCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(badge, size = 34.dp)
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    entry.note ?: badge.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    entry.occurredOn.formatDayMonth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                (if (income) "+" else "-") + entry.amount.formatMoney(currency),
                style = MonoFigure,
                color = if (income) DividendColors.Growth else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove this record",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun MonthRow(month: MonthlyLedgerTotalDto, currency: String) {
    val positive = month.net.signum() >= 0

    DsCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    month.month.toDisplayMonth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${month.entryCount} recorded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (positive) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = if (positive) DividendColors.Growth else DividendColors.Danger,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                month.net.abs().formatMoney(currency),
                style = MonoFigure,
                color = if (positive) DividendColors.Growth else DividendColors.Danger,
            )
        }
    }
}

// --- small pieces ------------------------------------------------------------

@Composable
internal fun IconBadge(icon: LedgerIcon, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(icon.tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon.icon,
            contentDescription = null,
            tint = icon.tint,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
private fun AddChip(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        OverlineText(label, color = tint)
    }
}

@Composable
private fun HintCard(message: String) {
    DsCard(modifier = Modifier.fillMaxWidth()) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }
}

/** `2026-08` reads as `August 2026`; anything unexpected is shown as it arrived. */
private fun String.toDisplayMonth(): String =
    runCatching {
        YearMonth.parse(this).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US))
    }.getOrDefault(this)

internal fun String.perPhrase(): String = when (this) {
    "DAILY" -> "a day"
    "WEEKLY" -> "a week"
    "MONTHLY" -> "a month"
    "YEARLY" -> "a year"
    else -> lowercase(Locale.US)
}
