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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.Precision
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.core.formatAmount
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.core.formatPercent
import com.dividendstream.app.data.remote.FundDto
import com.dividendstream.app.data.remote.LedgerDto
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.SectionHeader
import com.dividendstream.app.ui.theme.DividendColors
import com.dividendstream.app.ui.theme.MonoFigure
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * One fund, on its own.
 *
 * The ledger's fund list used to carry every control a fund had: two buttons, three of its
 * movements, an "and N more" that led nowhere, and a delete. Three funds filled a phone, the
 * balance you wanted to compare was the hardest figure on the card to find, and the history
 * was visible but not reachable.
 *
 * So the list keeps what you compare funds by and this screen takes what you do to one. The
 * cost is a tap before you can add money, which is the right thing to charge for: putting
 * money into a fund happens a few times a month, and writing down a lunch -- which stayed
 * exactly where it was -- happens every day.
 *
 * It shares the ledger's ViewModel rather than fetching its own copy. Every figure here is
 * derived from the same month the list is showing, and a second fetch would give this screen
 * its own idea of the surplus, which is the sort of disagreement nobody can explain later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundDetailScreen(
    viewModel: LedgerViewModel,
    fundId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ledger = state.ledger
    val fund = ledger?.funds?.firstOrNull { it.id == fundId }
    var editor by remember { mutableStateOf<LedgerEditor?>(null) }

    // Deleting the fund is the ordinary way to leave this screen, and a fund that is gone has
    // nothing left to draw. Waiting for the ledger to arrive first keeps the first frame --
    // where nothing has loaded and every fund is missing -- from bouncing straight back out.
    LaunchedEffect(ledger, fund) {
        if (ledger != null && fund == null) onBack()
    }

    val badge = LedgerIcon.of(fund?.icon)

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            badge.emoji,
                            fontSize = with(LocalDensity.current) { 18.dp.toSp() },
                            lineHeight = with(LocalDensity.current) { 18.dp.toSp() },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            fund?.name ?: "Fund",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { padding ->
        if (ledger == null || fund == null) {
            LoadingBox(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                FundBalanceCard(fund, ledger, state.streams, viewModel.serverClock)
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(
                        label = "Spend from it",
                        tint = DividendColors.Danger,
                        modifier = Modifier.weight(1f),
                    ) { editor = LedgerEditor.Movement(fund, "WITHDRAWAL") }
                    ActionButton(
                        label = "Put money in",
                        tint = DividendColors.Growth,
                        modifier = Modifier.weight(1f),
                    ) { editor = LedgerEditor.Movement(fund, "DEPOSIT") }
                }
            }

            item {
                SettingsRow(
                    label = "Name and share",
                    value = "${fund.name} -- ${fund.percent.formatPercent(0)}",
                    onClick = { editor = LedgerEditor.Fund(fund) },
                )
            }

            item { SectionHeader("Everything in and out") }

            if (fund.movements.isEmpty()) {
                item {
                    HintCard(
                        "Nothing has been moved by hand. The share above fills this fund on " +
                            "its own; these are the times you put something in or took " +
                            "something out yourself.",
                    )
                }
            } else {
                item { MovementTotals(fund, ledger.currency) }
                // Every one of them. The list had room for three and summarised the rest,
                // which told you how much history you could not see.
                items(fund.movements, key = { it.id }) { movement ->
                    DsCard(modifier = Modifier.fillMaxWidth()) {
                        MovementRow(
                            movement = movement,
                            currency = ledger.currency,
                            onEdit = {
                                editor = LedgerEditor.Movement(fund, movement.direction, movement)
                            },
                            onDelete = { viewModel.deleteFundMovement(movement.id) },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
            item { DeleteFundCard(fund) { viewModel.deleteFund(fund.id) } }
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

/**
 * What the fund holds, large and moving.
 *
 * The same arithmetic the list row does, for the same reason: the share fills the fund
 * per frame, and a screen that recomputed it differently would disagree with the row you
 * tapped to get here.
 */
@Composable
private fun FundBalanceCard(
    fund: FundDto,
    ledger: LedgerDto,
    streams: LedgerStreams,
    clock: ServerClock,
) {
    val badge = LedgerIcon.of(fund.icon)
    val net by rememberNetAccrued(streams, clock)
    val share = remember(fund) { fund.percent.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP) }
    val accruing = if (net.signum() <= 0) BigDecimal.ZERO else net.multiply(share)
    val holding = fund.carriedOver.add(accruing)
    val target = fund.plannedThisMonth
    val borrowed = holding.signum() < 0
    val progress =
        if (target.signum() <= 0) 0f
        else accruing.divide(target, 6, RoundingMode.DOWN).toFloat().coerceIn(0f, 1f)

    DsCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        OverlineText(if (borrowed) "owed back to this fund" else "in the fund")
        Spacer(Modifier.height(8.dp))
        Text(
            (if (borrowed) "-" else "") + holding.abs().formatAmount(Precision.AMOUNT),
            style = MonoFigure.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize),
            color = if (borrowed) DividendColors.Danger else badge.tint,
            maxLines = 1,
        )

        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = if (borrowed) DividendColors.Danger else badge.tint,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        Text(
            when {
                borrowed && target.signum() > 0 ->
                    "Paying itself back at ${target.formatMoney(ledger.currency)} a month"
                target.signum() > 0 ->
                    "+${accruing.formatAmount(Precision.AMOUNT)} of " +
                        "${target.formatMoney(ledger.currency)} this month"
                else -> "Nothing to put aside this month"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${fund.percent.formatPercent(0)} of whatever is left over, every month, " +
                "collected second by second.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** In, out, and what the two come to. Counted from the movements, not the balance. */
@Composable
private fun MovementTotals(fund: FundDto, currency: String) {
    val paidIn = fund.movements
        .filter { it.direction == "DEPOSIT" }
        .fold(BigDecimal.ZERO) { sum, it -> sum.add(it.amount) }
    val takenOut = fund.movements
        .filter { it.direction == "WITHDRAWAL" }
        .fold(BigDecimal.ZERO) { sum, it -> sum.add(it.amount) }

    DsCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                OverlineText("put in")
                Text(
                    paidIn.formatMoney(currency),
                    style = MonoFigure,
                    color = DividendColors.Growth,
                )
            }
            Column(Modifier.weight(1f)) {
                OverlineText("taken out")
                Text(
                    takenOut.formatMoney(currency),
                    style = MonoFigure,
                    color = DividendColors.Danger,
                )
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                OverlineText("by hand")
                Text(
                    paidIn.subtract(takenOut).formatMoney(currency),
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String, onClick: () -> Unit) {
    DsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Removing the fund, which is not the same as removing a line from it.
 *
 * It asks twice, and the second question says how much history goes with it. On the old card
 * this control was an identical bin icon one row above the bin that deletes a single
 * movement; here it is at the bottom, worded rather than drawn, and it counts what it takes.
 */
@Composable
private fun DeleteFundCard(fund: FundDto, onDelete: () -> Unit) {
    var armed by remember(fund.id) { mutableStateOf(false) }

    DsCard(modifier = Modifier.fillMaxWidth().clickable { if (armed) onDelete() else armed = true }) {
        Text(
            if (armed) "Tap again to delete it" else "Delete this fund",
            style = MaterialTheme.typography.titleSmall,
            color = DividendColors.Danger,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                armed && fund.movements.isNotEmpty() ->
                    "${fund.movements.size} recorded " +
                        (if (fund.movements.size == 1) "movement goes" else "movements go") +
                        " with it. What the share collected stops being set aside and " +
                        "goes back into what is left over."
                armed ->
                    "What the share collected stops being set aside and goes back into " +
                        "what is left over."
                else ->
                    "The ${fund.percent.formatPercent(0)} it takes returns to what is " +
                        "left over each month."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActionButton(
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = tint)
    }
}
