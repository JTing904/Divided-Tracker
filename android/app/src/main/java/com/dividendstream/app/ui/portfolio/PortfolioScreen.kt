package com.dividendstream.app.ui.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.core.formatFull
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.core.formatPrice
import com.dividendstream.app.core.formatPercent
import com.dividendstream.app.core.formatShares
import com.dividendstream.app.data.remote.HoldingDto
import com.dividendstream.app.data.remote.PortfolioDto
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.EmptyState
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.PrimaryButton
import com.dividendstream.app.ui.components.SectionHeader
import com.dividendstream.app.ui.components.PendingPurchaseRow
import com.dividendstream.app.ui.components.StaleDataBanner
import com.dividendstream.app.ui.components.StatTile
import com.dividendstream.app.ui.theme.MonoFigure
import java.time.Duration
import java.time.Instant

@Composable
fun PortfolioScreen(
    viewModel: PortfolioViewModel,
    serverClock: ServerClock,
    onAddStock: () -> Unit,
    onOpenStock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val portfolio = state.portfolio
    var pendingDelete by remember { mutableStateOf<HoldingDto?>(null) }

    pendingDelete?.let { holding ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove ${holding.companyName}?") },
            text = {
                Text(
                    "Dividends that have not yet been paid will be removed. Income you have " +
                        "already received stays in your history.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteHolding(holding.id)
                    pendingDelete = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    when {
        state.isLoading && portfolio == null -> LoadingBox(modifier.fillMaxSize())

        portfolio == null -> Column(modifier.fillMaxSize().padding(20.dp)) {
            state.error?.let { ErrorBanner(error = it, onRetry = viewModel::refresh) }
        }

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.isStale) {
                item {
                    StaleDataBanner(
                        cachedAgo = state.cachedAt.describeAge(serverClock),
                        reason = state.staleError,
                    )
                }
            }

            items(pending, key = { it.idempotencyKey }) { purchase ->
                PendingPurchaseRow(
                    companyName = purchase.companyName,
                    detail = "${purchase.quantity.formatShares()} shares at " +
                        purchase.averagePrice.formatPrice(state.portfolio?.currency ?: "MYR"),
                    failure = purchase.failure,
                    onRetry = { viewModel.retryPending(purchase.idempotencyKey) },
                    onDiscard = { viewModel.discardPending(purchase.idempotencyKey) },
                )
            }

            item { PortfolioSummary(portfolio) }

            state.actionError?.let { error ->
                item { ErrorBanner(error = error) }
            }

            if (state.isEmpty) {
                item {
                    EmptyState(
                        title = "Your portfolio is empty",
                        message = "Add the dividend stocks you own to start tracking expected income.",
                        action = { PrimaryButton("Add a stock", onAddStock) },
                    )
                }
            } else {
                item {
                    SectionHeader(
                        title = "Holdings",
                        action = {
                            TextButton(onClick = onAddStock) {
                                Text("Add", color = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }
                items(portfolio.holdings, key = { it.id }) { holding ->
                    HoldingCard(
                        holding = holding,
                        onClick = { onOpenStock(holding.symbol) },
                        onDelete = { pendingDelete = holding },
                    )
                }
            }
        }
    }
}

@Composable
private fun PortfolioSummary(portfolio: PortfolioDto) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DsCard(modifier = Modifier.fillMaxWidth()) {
            OverlineText("Total expected dividend")
            Spacer(Modifier.height(8.dp))
            Text(
                portfolio.totalExpectedDividend.formatMoney(portfolio.currency),
                style = MaterialTheme.typography.displayLarge.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Estimated across the cycles currently in flight",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(
                "Market value",
                portfolio.totalMarketValue.formatMoney(portfolio.currency),
                Modifier.weight(1f),
            )
            StatTile(
                "Cost basis",
                portfolio.totalCostBasis.formatMoney(portfolio.currency),
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HoldingCard(holding: HoldingDto, onClick: () -> Unit, onDelete: () -> Unit) {
    DsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    holding.companyName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${holding.exchange}: ${holding.symbol}",
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                OverlineText("Est. dividend")
                Spacer(Modifier.height(4.dp))
                Text(
                    holding.expectedDividend.formatMoney(holding.currency),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            LabelledFigure("Shares", holding.quantity.formatShares(), Modifier.weight(1f))
            LabelledFigure(
                "Yield",
                holding.dividendYieldPercent?.formatPercent() ?: "-",
                Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            LabelledFigure(
                "Avg price",
                holding.averagePrice.formatPrice(holding.currency),
                Modifier.weight(1f),
            )
            LabelledFigure(
                "Next payment",
                holding.nextPaymentDate?.formatFull() ?: "-",
                Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Remove ${holding.companyName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LabelledFigure(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        OverlineText(label)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MonoFigure, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

private fun Instant?.describeAge(clock: ServerClock): String {
    if (this == null) return "recently"
    val minutes = Duration.between(this, clock.now()).toMinutes()
    return when {
        minutes < 1 -> "moments ago"
        minutes < 60 -> "$minutes min ago"
        minutes < 1_440 -> "${minutes / 60} h ago"
        else -> "${minutes / 1_440} d ago"
    }
}
