package com.dividendstream.app.ui.addstock

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.core.formatPrice
import com.dividendstream.app.core.formatShares
import com.dividendstream.app.core.formatPercent
import com.dividendstream.app.data.remote.HoldingDto
import com.dividendstream.app.data.remote.ServerAvailability
import com.dividendstream.app.data.remote.StockSummaryDto
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.DsTextField
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.PrimaryButton
import com.dividendstream.app.ui.components.ServerWakingBanner
import com.dividendstream.app.ui.theme.MonoFigure
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddStockScreen(
    viewModel: AddStockViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val availability by viewModel.serverAvailability.status.collectAsStateWithLifecycle()

    // Ticks so the elapsed figure moves; a countdown that never changes reads as frozen.
    val waking = availability as? ServerAvailability.Status.Waking
    var elapsed by remember(waking) { mutableStateOf(0L) }
    LaunchedEffect(waking) {
        while (waking != null) {
            elapsed = Duration.between(waking.since, Instant.now()).seconds.coerceAtLeast(0)
            delay(1_000)
        }
    }

    LaunchedEffect(state.savedHoldingSymbol) {
        if (state.savedHoldingSymbol != null) {
            viewModel.consumeSaved()
            onSaved()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Add investment", style = MaterialTheme.typography.titleLarge) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DsTextField(
                label = "Stock or company",
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                placeholder = "Search e.g. Maybank, CIMB, 1155",
                leadingIcon = Icons.Default.Search,
            )

            if (state.isSearching) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Searching...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.results.forEach { result ->
                SearchResultCard(result, onSelect = { viewModel.onSelect(result) })
            }

            if (waking != null) {
                ServerWakingBanner(
                    elapsedSeconds = elapsed,
                    typicalSeconds = ServerAvailability.TYPICAL_WAKE_SECONDS,
                )
            }

            state.selected?.let { selected ->
                SelectedStockCard(selected, onClear = viewModel::clearSelection)

                DsTextField(
                    label = "Number of shares",
                    value = state.quantity,
                    onValueChange = viewModel::onQuantityChange,
                    placeholder = "1000",
                    keyboardType = KeyboardType.Decimal,
                )

                DsTextField(
                    // The same field means two things, and saying which one matters: typing a
                    // running average into a top-up would skew the position it is merged into.
                    label = if (state.isTopUp) "Price paid per share" else "Average purchase price",
                    value = state.averagePrice,
                    onValueChange = viewModel::onAveragePriceChange,
                    placeholder = "9.80",
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done,
                    supportingText = if (state.isTopUp) "What you paid this time, not your average" else null,
                )

                state.existing?.let { held -> TopUpSummary(held, state) }

                DividendProjection(state)
            }

            state.error?.let { error ->
                ErrorBanner(error = error)
            }

            if (state.selected != null) {
                PrimaryButton(
                    text = if (state.isTopUp) "Add to position" else "Add to portfolio",
                    onClick = viewModel::submit,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canSubmit,
                    loading = state.isSubmitting,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SearchResultCard(stock: StockSummaryDto, onSelect: () -> Unit) {
    DsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SymbolBadge(stock.companyName)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stock.companyName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${stock.symbol} · ${stock.exchange} · ${stock.currency}",
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            stock.lastPrice?.let { price ->
                Text(
                    price.formatMoney(stock.currency),
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SelectedStockCard(stock: StockSummaryDto, onClear: () -> Unit) {
    DsCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SymbolBadge(stock.companyName)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stock.companyName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${stock.symbol} · ${stock.currency}",
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Choose a different stock",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What this position is expected to pay, shown before the user commits.
 * Explicitly labelled an estimate -- the backend recalculates it on save.
 */
@Composable
private fun DividendProjection(state: AddStockUiState) {
    val detail = state.selectedDetail ?: return
    val currency = detail.currency

    DsCard(modifier = Modifier.fillMaxWidth()) {
        OverlineText("Dividend projection")
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    "Expected dividend",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.expectedDividend?.formatMoney(currency) ?: "Enter shares",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                OverlineText("Yield")
                Spacer(Modifier.height(4.dp))
                Text(
                    detail.dividendYieldPercent?.formatPercent() ?: "-",
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Declared payout",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                detail.dividendPerShare?.let { "${it.formatMoney(currency, 4)} / share" } ?: "Not available",
                style = MonoFigure,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        if (detail.dividendPerShare == null) {
            Spacer(Modifier.height(10.dp))
            Text(
                "No dividend data is available for this stock yet. You can still add it and " +
                    "enter the dividend details later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Two-letter monogram, standing in for a company logo. */
@Composable
private fun SymbolBadge(companyName: String) {
    val initials = companyName
        .split(' ')
        .filter { it.isNotBlank() }
        .take(2)
        .map { it.first().uppercaseChar() }
        .joinToString("")

    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, style = MonoFigure, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Spells out what pressing the button does to an existing position.
 *
 * The weighted average is arithmetic the owner used to have to do themselves, and the point of
 * showing it is that a purchase changes the cost basis -- quietly, if nobody says so.
 */
@Composable
private fun TopUpSummary(held: HoldingDto, state: AddStockUiState) {
    DsCard(modifier = Modifier.fillMaxWidth()) {
        OverlineText("You already hold")
        Spacer(Modifier.height(6.dp))
        Text(
            "${held.quantity.formatShares()} shares at " +
                held.averagePrice.formatPrice(held.currency),
            style = MonoFigure,
            color = MaterialTheme.colorScheme.onSurface,
        )

        state.mergedPreview?.let { (quantity, averagePrice) ->
            Spacer(Modifier.height(14.dp))
            OverlineText("After this purchase")
            Spacer(Modifier.height(6.dp))
            Text(
                "${quantity.formatShares()} shares at " +
                    averagePrice.formatPrice(held.currency),
                style = MonoFigure,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Your average is worked out for you. The figure the server stores is the one " +
                    "that counts.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
