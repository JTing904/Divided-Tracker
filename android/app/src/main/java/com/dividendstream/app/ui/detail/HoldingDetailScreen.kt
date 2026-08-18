package com.dividendstream.app.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.Precision
import com.dividendstream.app.core.ServerClock
import com.dividendstream.app.core.formatFull
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.core.formatPrice
import com.dividendstream.app.core.formatPercent
import com.dividendstream.app.core.formatShares
import com.dividendstream.app.ui.components.AccrualProgressBar
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LiveAmountText
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.SectionHeader
import com.dividendstream.app.ui.components.StatTile
import com.dividendstream.app.ui.components.StatusPill
import com.dividendstream.app.ui.components.rememberAccruedAmount
import com.dividendstream.app.ui.theme.DividendColors
import com.dividendstream.app.ui.theme.MonoFigure
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HoldingDetailScreen(
    viewModel: HoldingDetailViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val stock = state.stock

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stock?.symbol?.let { "${stock.companyName} ($it)" } ?: "Loading",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                    )
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
        when {
            state.isLoading && stock == null -> LoadingBox(Modifier.fillMaxSize().padding(padding))

            stock == null -> Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                state.error?.let { ErrorBanner(error = it, onRetry = viewModel::refresh) }
            }

            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PriceHeader(
                    price = stock.lastPrice?.formatMoney(stock.currency) ?: "Price unavailable",
                    exchange = stock.exchange,
                    sector = stock.sector,
                )

                LiveAccumulationCard(state, viewModel.serverClock)

                SectionHeader("Your holding")

                state.holding?.let { holding ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Shares", holding.quantity.formatShares(), Modifier.weight(1f))
                        StatTile(
                            "Market value",
                            holding.marketValue?.formatMoney(holding.currency) ?: "-",
                            Modifier.weight(1f),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(
                            "Avg price",
                            holding.averagePrice.formatPrice(holding.currency),
                            Modifier.weight(1f),
                        )
                        StatTile(
                            "Cost basis",
                            holding.costBasis.formatMoney(holding.currency),
                            Modifier.weight(1f),
                        )
                    }
                } ?: DsCard(Modifier.fillMaxWidth()) {
                    Text(
                        "You do not currently hold this stock.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionHeader("Dividend")

                DsCard(Modifier.fillMaxWidth()) {
                    DetailRow("Dividend per share", stock.dividendPerShare?.formatMoney(stock.currency, 4) ?: "-")
                    DetailRow("Dividend yield", stock.dividendYieldPercent?.formatPercent() ?: "-")
                    DetailRow("Frequency", stock.dividendFrequency?.replace('_', ' ') ?: "-")
                    DetailRow("Ex-dividend date", stock.exDate?.formatFull() ?: "-")
                    DetailRow("Record date", stock.recordDate?.formatFull() ?: "-")
                    DetailRow("Payment date", stock.nextPaymentDate?.formatFull() ?: "-", isLast = true)
                }

                Text(
                    "Ex-dividend and record dates come from the market data provider. The " +
                        "accumulation shown above is an estimate of the expected payment, not " +
                        "money already received.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PriceHeader(price: String, exchange: String, sector: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(price, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.width(12.dp))
        Column {
            OverlineText(exchange)
            sector?.let {
                Spacer(Modifier.height(2.dp))
                OverlineText(it)
            }
        }
    }
}

@Composable
private fun LiveAccumulationCard(state: HoldingDetailUiState, clock: ServerClock) {
    val accrued by rememberAccruedAmount(state.accumulationStreams, clock)
    val stream = state.liveStreams.firstOrNull()
    val currency = stream?.currency ?: state.stock?.currency ?: "MYR"

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
            contentPadding = PaddingValues(vertical = 22.dp, horizontal = 20.dp),
            border = null,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OverlineText("Live accumulation", color = DividendColors.Growth)
                stream?.let { StatusPill(it.status) }
            }

            Spacer(Modifier.height(12.dp))

            LiveAmountText(amount = accrued, currency = currency, modifier = Modifier.fillMaxWidth())

            Spacer(Modifier.height(10.dp))

            Text(
                text = stream?.let {
                    "${it.ratePerSecond.formatMoney(currency, Precision.RATE)} / sec  ·  " +
                        "of ${it.expectedAmount.formatMoney(currency)} expected"
                } ?: "Not currently accumulating",
                style = MonoFigure,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )

            stream?.let {
                Spacer(Modifier.height(14.dp))
                AccrualProgressBar(progress = it.progress.toFloat())
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String, isLast: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MonoFigure, color = MaterialTheme.colorScheme.onSurface)
    }
    if (!isLast) Spacer(Modifier.height(12.dp))
}
