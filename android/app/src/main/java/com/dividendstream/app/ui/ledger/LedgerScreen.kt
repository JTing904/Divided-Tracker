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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.dividendstream.app.data.remote.FundMovementDto
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
    onOpenFund: (String) -> Unit,
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
            item { PeriodHeader(state, viewModel::setPeriod) }

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

            item { FundTotalCard(state, viewModel.serverClock) }

            item { AllocationBar(ledger) }

            if (ledger.funds.isEmpty()) {
                item { HintCard("Split what is left into funds -- an emergency pot, a trip, an investment. Give each one a percentage and it fills itself, second by second, and keeps what it collected when the month turns over. Spend from it and the amount comes off.") }
            } else {
                item { FundSortRow(state.fundSort, viewModel::setFundSort) }
                items(state.sortedFunds, key = { it.id }) { fund ->
                    FundRow(
                        fund = fund,
                        ledger = ledger,
                        streams = state.streams,
                        clock = viewModel.serverClock,
                        onOpen = { onOpenFund(fund.id) },
                    )
                }
            }

            item {
                // Two chips rather than one "Record" button. The form has always had a
                // Spent/Received switch, but it was two taps and a glance inside a form to
                // find, under a heading that said the section was for spending -- so money
                // coming in got written down as a deposit into a fund instead, which moves
                // an amount around without it ever having been earned.
                SectionHeader("What happened once") {
                    Row {
                        AddChip("In", DividendColors.Growth) {
                            editor = LedgerEditor.Entry("INCOME")
                        }
                        Spacer(Modifier.width(8.dp))
                        AddChip("Out", DividendColors.Danger) {
                            editor = LedgerEditor.Entry("EXPENSE")
                        }
                    }
                }
            }

            item { ActualTotals(ledger) }

            if (state.spentByCategory.isNotEmpty()) {
                item { CategoryChart(state.spentByCategory, ledger.currency) }
            }

            if (ledger.entries.isEmpty()) {
                item {
                    HintCard(
                        "Things that happen once rather than every month. Out for a lunch, " +
                            "petrol, a taxi; In for a side job, a refund, a gift. Each one " +
                            "moves what is left above.",
                    )
                }
            } else {
                if (state.period == LedgerPeriod.Month) {
                    item {
                        SpendingCalendar(
                            month = ledger.month,
                            spentByDay = state.spentByDay,
                            selected = state.selectedDay,
                            currency = ledger.currency,
                            onSelect = viewModel::selectDay,
                        )
                    }
                }
                item { SortRow(state.sort, viewModel::setSort) }

                val shown = state.sortedEntries
                if (shown.isEmpty()) {
                    item {
                        HintCard("Nothing written down on that day. Tap it again to see the month.")
                    }
                } else {
                    items(shown, key = { it.id }) { entry ->
                        EntryRow(
                            entry = entry,
                            currency = ledger.currency,
                            onEdit = { editor = LedgerEditor.Entry(entry.direction, entry) },
                            onDelete = { viewModel.deleteEntry(entry.id) },
                        )
                    }
                }
            }

            val past = ledger.months.filter { it.entryCount > 0 }
            if (past.isNotEmpty()) {
                item { SectionHeader("Earlier months") }
                item { MonthChart(ledger.months, ledger.currency) }
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
private fun PeriodHeader(state: LedgerUiState, onSelect: (LedgerPeriod) -> Unit) {
    val ledger = state.ledger ?: return

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LedgerPeriod.entries.forEach { option ->
                val selected = option == state.period
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.surface,
                        )
                        .clickable { onSelect(option) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (state.period) {
                LedgerPeriod.Day -> "Starts again at midnight. What you spend today still " +
                    "counts against the month."
                LedgerPeriod.Month -> when (ledger.daysLeftInMonth) {
                    1L -> "Starts again tomorrow"
                    else -> "Starts again in ${ledger.daysLeftInMonth} days"
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NetCounterCard(state: LedgerUiState, clock: ServerClock) {
    val ledger = state.ledger ?: return
    // The plan, ticking, plus whatever the records come to. Recording a RM12 lunch takes RM12
    // off the figure -- which is what a person writing it down means it to do.
    val planned by rememberNetAccrued(state.streams, clock)
    val net = planned.add(ledger.recordedNet)
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
                OverlineText(
                    when {
                        negative && state.period == LedgerPeriod.Day -> "Short today"
                        negative -> "Short this month"
                        state.period == LedgerPeriod.Day -> "Left over today"
                        else -> "Left over this month"
                    },
                )
                Spacer(Modifier.height(10.dp))
                SignedLiveAmountText(amount = net, currency = ledger.currency)
                Spacer(Modifier.height(12.dp))
                Text(
                    ledger.netRatePerSecond.paceSentence(ledger),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ledger.recordedNet.signum() != 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Includes ${ledger.recordedNet.abs().formatMoney(ledger.currency)} " +
                            (if (ledger.recordedNet.signum() < 0) "you wrote down as spent"
                            else "you wrote down as received"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    // Both sides include what was written down, so the two tiles still add up to the figure
    // above them. Splitting the records out of the totals would leave a person checking the
    // arithmetic and finding it wrong.
    val plannedIncome by rememberSideAccrued(state.streams.income, clock)
    val plannedExpense by rememberSideAccrued(state.streams.expense, clock)
    val income = plannedIncome.add(ledger.actualIncome)
    val expense = plannedExpense.add(ledger.actualExpense)

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

/**
 * Everything across the funds, ticking.
 *
 * Summed from the same per-frame figures the rows show, not from the server's snapshot, so
 * the total and the rows below it always agree -- a total that lagged its own parts by a
 * refresh would be the first thing anyone noticed.
 */
@Composable
private fun FundTotalCard(state: LedgerUiState, clock: ServerClock) {
    val ledger = state.ledger ?: return
    val net by rememberFundSurplus(state.streams, ledger.monthRecordedNet, clock)
    val accruing = if (net.signum() <= 0) BigDecimal.ZERO else net
    val total = remember(ledger.funds) { ledger.funds }
        .fold(BigDecimal.ZERO) { sum, fund ->
            val share = fund.percent.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP)
            sum.add(fund.carriedOver).add(accruing.multiply(share))
        }
    val borrowed = total.signum() < 0

    DsCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        OverlineText(if (borrowed) "Owed back across your funds" else "In your funds")
        Spacer(Modifier.height(8.dp))
        SignedLiveAmountText(
            amount = total,
            currency = ledger.currency,
            decimals = Precision.AMOUNT,
            steadyDecimals = 2,
            style = MonoFigure,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Across ${ledger.funds.size} " + if (ledger.funds.size == 1) "fund" else "funds",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FundSortRow(sort: FundSort, onSelect: (FundSort) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OverlineText("Sort")
        Spacer(Modifier.width(10.dp))
        FundSort.entries.forEach { option ->
            val selected = option == sort
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) DividendColors.GrowthGlow
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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

/**
 * One fund, as much of it as belongs in a list.
 *
 * Everything you would compare two funds by and nothing you would do to one: the buttons, the
 * movements and the delete moved to the fund's own screen. Six things and two controls per
 * card meant three funds filled a phone, and the figure you actually wanted to compare was
 * the hardest thing on the card to find.
 *
 * The delete going with them matters more than the room it saves. It sat one row above the
 * delete on a movement, drawn with the same icon at almost the same size, and the two mean
 * very different things: one removes a line of history, the other removes the fund and every
 * line in it.
 */
@Composable
private fun FundRow(
    fund: FundDto,
    ledger: LedgerDto,
    streams: LedgerStreams,
    clock: ServerClock,
    onOpen: () -> Unit,
) {
    val badge = LedgerIcon.of(fund.icon)
    val net by rememberFundSurplus(streams, ledger.monthRecordedNet, clock)
    // A fund takes its share of the surplus, and there is no share of a deficit.
    val share = remember(fund) { fund.percent.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP) }
    val accruing = if (net.signum() <= 0) BigDecimal.ZERO else net.multiply(share)
    // The settled part plus this month's share, recomputed each frame. The same sum the
    // server does, which is what stops the figure jumping when a refresh lands.
    val holding = fund.carriedOver.add(accruing)
    val target = fund.plannedThisMonth
    val progress =
        if (target.signum() <= 0) 0f
        else accruing.divide(target, 6, RoundingMode.DOWN).toFloat().coerceIn(0f, 1f)

    DsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
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

            // The headline is what the fund holds, and it moves while you watch it: the share
            // does the filling, so there is nothing to press. Below zero it is a debt to the
            // fund rather than a smaller balance, and it says so in as many words.
            val borrowed = holding.signum() < 0
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    (if (borrowed) "-" else "") + holding.abs().formatAmount(Precision.AMOUNT),
                    style = MonoFigure,
                    color = if (borrowed) DividendColors.Danger else badge.tint,
                    maxLines = 1,
                )
                OverlineText(if (borrowed) "owed back" else "in the fund")
            }

            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Open ${fund.name}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (holding.signum() < 0) DividendColors.Danger else badge.tint,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Text(
            when {
                holding.signum() < 0 && target.signum() > 0 ->
                    "Paying itself back at ${target.formatMoney(ledger.currency)} a month"
                target.signum() > 0 ->
                    "+${accruing.formatAmount(Precision.AMOUNT)} of " +
                        "${target.formatMoney(ledger.currency)} this month"
                else -> "Nothing to put aside this month"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One movement, correctable.
 *
 * Tapping edits rather than only offering a delete, because a mistyped amount is a correction
 * to one thing that happened, not two things that cancel out. Deleting and re-entering would
 * leave the same balance and a history that says the money went in twice and came out once.
 */
@Composable
internal fun MovementRow(
    movement: FundMovementDto,
    currency: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val deposit = movement.direction == "DEPOSIT"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onEdit)
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            movement.occurredOn.formatDayMonth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            movement.note ?: if (deposit) "Put in" else "Taken out",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            (if (deposit) "+" else "-") + movement.amount.formatMoney(currency),
            style = MaterialTheme.typography.bodySmall,
            color = if (deposit) DividendColors.Growth else DividendColors.Danger,
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove this entry",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

// --- records -----------------------------------------------------------------

/**
 * The month at a glance, with the days something was spent on marked.
 *
 * Records are never deleted, so a month's worth of them is longer than everything else on the
 * screen put together. A grid of thirty-one cells says the same thing in six lines and turns
 * the list into something you go to rather than something you scroll past.
 *
 * A day with nothing on it is not tappable: there would be nothing to show, and a cell that
 * highlights and then reveals an empty list is a small lie about there being something there.
 */
@Composable
private fun SpendingCalendar(
    month: String,
    spentByDay: Map<java.time.LocalDate, BigDecimal>,
    selected: java.time.LocalDate?,
    currency: String,
    onSelect: (java.time.LocalDate?) -> Unit,
) {
    val first = remember(month) {
        runCatching { YearMonth.parse(month).atDay(1) }.getOrElse { java.time.LocalDate.now().withDayOfMonth(1) }
    }
    val days = first.lengthOfMonth()
    // Monday-first, so the grid matches how a week is written here.
    val leading = (first.dayOfWeek.value + 6) % 7
    val busiest = spentByDay.values.maxOfOrNull { it.abs() } ?: BigDecimal.ONE
    val today = java.time.LocalDate.now()

    DsCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlineText(if (selected == null) "Whole month" else selected.formatDayMonth())
            if (selected != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onSelect(null) }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "Show the month",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))

        var day = 1
        while (day <= days) {
            Row(Modifier.fillMaxWidth()) {
                repeat(7) { column ->
                    val index = (day - 1) + column
                    val isLeading = day == 1 && column < leading
                    val number = if (day == 1) index - leading + 1 else day + column
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (isLeading || number < 1 || number > days) {
                            Spacer(Modifier.height(38.dp))
                        } else {
                            val date = first.withDayOfMonth(number)
                            DayCell(
                                number = number,
                                spent = spentByDay[date],
                                busiest = busiest,
                                isSelected = date == selected,
                                isToday = date == today,
                                onClick = { onSelect(date) },
                            )
                        }
                    }
                }
            }
            day += if (day == 1) 7 - leading else 7
        }

        selected?.let { date ->
            spentByDay[date]?.let { amount ->
                Spacer(Modifier.height(8.dp))
                Text(
                    if (amount.signum() >= 0) {
                        "${amount.formatMoney(currency)} spent on ${date.formatDayMonth()}"
                    } else {
                        "${amount.abs().formatMoney(currency)} received on ${date.formatDayMonth()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DayCell(
    number: Int,
    spent: BigDecimal?,
    busiest: BigDecimal,
    isSelected: Boolean,
    isToday: Boolean,
    onClick: () -> Unit,
) {
    val has = spent != null && spent.signum() != 0
    // The heavier the day, the stronger the wash. Floors at a visible level so a small day is
    // not indistinguishable from an empty one.
    val weight = if (!has || busiest.signum() == 0) 0f else {
        spent!!.abs().divide(busiest, 4, RoundingMode.DOWN).toFloat().coerceIn(0.18f, 1f)
    }
    val tint = if (spent != null && spent.signum() < 0) DividendColors.Growth else DividendColors.Danger

    Box(
        modifier = Modifier
            .padding(2.dp)
            .size(38.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                when {
                    isSelected -> tint.copy(alpha = 0.45f)
                    has -> tint.copy(alpha = 0.10f + 0.25f * weight)
                    else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                },
            )
            .then(if (has) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = when {
                isSelected -> MaterialTheme.colorScheme.onSurface
                isToday -> MaterialTheme.colorScheme.primary
                has -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/**
 * Where the money went, as bars.
 *
 * Drawn with boxes rather than a charting library: a horizontal bar is a rectangle whose width
 * is a fraction, the desktop build compiles these same sources, and a dependency that has to
 * work on both platforms is a lot to take on for a rectangle.
 *
 * Each bar carries its own category colour, so the chart and the rows below it are recognisably
 * about the same things.
 */
@Composable
private fun CategoryChart(
    spent: List<Pair<LedgerIcon, BigDecimal>>,
    currency: String,
) {
    val total = spent.fold(BigDecimal.ZERO) { sum, it -> sum.add(it.second) }
    if (total.signum() <= 0) return
    val biggest = spent.first().second

    DsCard(modifier = Modifier.fillMaxWidth()) {
        OverlineText("Where it went")
        Spacer(Modifier.height(12.dp))

        spent.forEach { (icon, amount) ->
            val share = amount.divide(total, 4, RoundingMode.DOWN)
            // Bars are scaled against the biggest, not against the total: with eight
            // categories every bar would otherwise be a stub.
            val width = amount.divide(biggest, 4, RoundingMode.DOWN).toFloat().coerceIn(0.04f, 1f)

            Column(Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        icon.emoji,
                        fontSize = with(LocalDensity.current) { 15.dp.toSp() },
                        lineHeight = with(LocalDensity.current) { 15.dp.toSp() },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        icon.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        amount.formatMoney(currency),
                        style = MonoFigure,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        share.multiply(BigDecimal("100")).formatPercent(0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(5.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(7.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(width)
                            .height(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(icon.tint),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "${total.formatMoney(currency)} written down, across ${spent.size} " +
                if (spent.size == 1) "category" else "categories",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Twelve months of what was written down, in and out.
 *
 * Records only, which is why the caption says so: the recurring flows are a projection and
 * projecting them backwards through a year would draw a history that never happened.
 *
 * Months with nothing in them are drawn as empty columns rather than skipped -- a gap in a
 * chart reads as missing data, and a quiet month is not missing.
 */
@Composable
private fun MonthChart(months: List<MonthlyLedgerTotalDto>, currency: String) {
    if (months.isEmpty()) return
    // Oldest on the left, which is the direction time is drawn in.
    val ordered = remember(months) { months.reversed() }
    val tallest = ordered.flatMap { listOf(it.income, it.expense) }
        .maxOfOrNull { it } ?: BigDecimal.ZERO
    if (tallest.signum() <= 0) return

    DsCard(modifier = Modifier.fillMaxWidth()) {
        OverlineText("Month by month")
        Spacer(Modifier.height(4.dp))
        Text(
            "Highest month: ${tallest.formatMoney(currency)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth().height(110.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ordered.forEach { month ->
                fun fraction(value: BigDecimal): Float =
                    if (value.signum() <= 0) 0f
                    else value.divide(tallest, 4, RoundingMode.DOWN).toFloat().coerceIn(0.02f, 1f)

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.height(88.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Bar(fraction(month.income), DividendColors.Growth)
                        Bar(fraction(month.expense), DividendColors.Danger)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        month.month.takeLast(2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Legend("In", DividendColors.Growth)
            Spacer(Modifier.width(14.dp))
            Legend("Out", DividendColors.Danger)
            Spacer(Modifier.weight(1f))
            Text(
                "What you wrote down only",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Bar(fraction: Float, tint: Color) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight(fraction)
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
            .background(if (fraction <= 0f) Color.Transparent else tint),
    )
}

@Composable
private fun Legend(label: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(tint))
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SortRow(sort: LedgerSort, onSelect: (LedgerSort) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OverlineText("Sort")
        Spacer(Modifier.width(10.dp))
        LedgerSort.entries.forEach { option ->
            val selected = option == sort
            Box(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (selected) DividendColors.GrowthGlow
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .clickable { onSelect(option) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

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
private fun EntryRow(
    entry: LedgerEntryDto,
    currency: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val income = entry.direction == "INCOME"
    val badge = LedgerIcon.of(entry.category)

    DsCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
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

/**
 * The emoji on its tinted disc.
 *
 * Drawn as text, because that is what an emoji is. The font size is derived from the disc so
 * the two scale together, and `platformStyle` turns off the extra line padding Android adds
 * above and below a glyph -- without it the emoji sits visibly high in its circle.
 */
@Composable
internal fun IconBadge(icon: LedgerIcon, size: androidx.compose.ui.unit.Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(icon.tint.copy(alpha = 0.26f), icon.tint.copy(alpha = 0.10f)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon.emoji,
            fontSize = with(LocalDensity.current) { (size * 0.5f).toSp() },
            lineHeight = with(LocalDensity.current) { (size * 0.5f).toSp() },
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun AddChip(
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
internal fun HintCard(message: String) {
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
