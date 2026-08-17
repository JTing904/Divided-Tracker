package com.dividendstream.app.ui.history

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.data.remote.DividendDto
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.EmptyState
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.SectionHeader
import com.dividendstream.app.ui.components.StatTile
import com.dividendstream.app.ui.theme.MonoFigure
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthHeading = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history = state.history

    when {
        state.isLoading && history == null -> LoadingBox(modifier.fillMaxSize())

        history == null -> Column(modifier.fillMaxSize().padding(20.dp)) {
            state.error?.let { ErrorBanner(error = it, onRetry = viewModel::refresh) }
        }

        state.isEmpty -> EmptyState(
            modifier = modifier.fillMaxSize(),
            title = "No dividends received yet",
            message = "Once a dividend reaches its payment date it is recorded here as income received.",
        )

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        "Total received",
                        history.totalReceived.formatMoney(history.currency),
                        Modifier.weight(1f),
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                    StatTile(
                        "This year",
                        history.receivedThisYear.formatMoney(history.currency),
                        Modifier.weight(1f),
                    )
                }
            }

            history.months.forEach { month ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OverlineText(formatMonthLabel(month.month))
                        Text(
                            month.total.formatMoney(history.currency),
                            style = MonoFigure,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                items(month.items, key = { it.id }) { dividend ->
                    PaidDividendRow(dividend)
                }
            }

            if (history.byStock.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("By stock")
                }
                item {
                    DsCard(modifier = Modifier.fillMaxWidth()) {
                        history.byStock.forEachIndexed { index, total ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        total.companyName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                    Text(
                                        total.symbol,
                                        style = MonoFigure,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    total.total.formatMoney(history.currency),
                                    style = MonoFigure,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaidDividendRow(dividend: DividendDto) {
    DsCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dividend.paymentDate.dayOfMonth.toString().padStart(2, '0'),
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    dividend.companyName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                OverlineText(dividend.status, color = MaterialTheme.colorScheme.primary)
            }

            // History shows what was actually paid, never the estimate.
            Text(
                "+${(dividend.paidAmount ?: dividend.expectedAmount).formatMoney(dividend.currency)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** `2026-08` from the API becomes `AUGUST 2026` for display. */
private fun formatMonthLabel(isoMonth: String): String =
    runCatching { YearMonth.parse(isoMonth).atDay(1).format(monthHeading) }.getOrDefault(isoMonth)
