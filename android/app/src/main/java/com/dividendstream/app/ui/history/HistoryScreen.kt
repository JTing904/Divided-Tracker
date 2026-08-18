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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.data.remote.DividendDto
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.DsTextField
import com.dividendstream.app.ui.components.EmptyState
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.SectionHeader
import com.dividendstream.app.ui.components.StatTile
import com.dividendstream.app.ui.theme.MonoFigure
import java.time.LocalDate
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

    state.confirming?.let { dividend ->
        ConfirmDateDialog(
            dividend = dividend,
            isSubmitting = state.isConfirming,
            onDismiss = viewModel::cancelConfirming,
            onConfirm = viewModel::confirmReceived,
        )
    }
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
                    PaidDividendRow(dividend, onConfirmDate = { viewModel.startConfirming(dividend) })
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
private fun PaidDividendRow(dividend: DividendDto, onConfirmDate: () -> Unit) {
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

        // Settled against an estimated date until somebody says otherwise. Saying so is worth
        // more than the row it corrects: it is the only evidence this issuer's real payment lag
        // can be learned from, and every later estimate for the stock improves with it.
        if (!dividend.paymentDateConfirmed) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Date estimated",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onConfirmDate) { Text("Set actual date") }
            }
        }
    }
}

/**
 * Asks for the day the money arrived.
 *
 * A typed date rather than a picker: this screen is shared with the desktop build, and one
 * field that behaves identically on both beats two that behave differently. Prefilled with the
 * estimate, which is usually within days of the truth.
 */
@Composable
private fun ConfirmDateDialog(
    dividend: DividendDto,
    isSubmitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    var text by remember(dividend.id) { mutableStateOf(dividend.paymentDate.toString()) }
    val parsed = remember(text) { runCatching { LocalDate.parse(text.trim()) }.getOrNull() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("When did it arrive?") },
        text = {
            Column {
                Text(
                    "${dividend.companyName} - the date shown is this app's estimate. Entering " +
                        "the real one also improves every future estimate for this stock.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                DsTextField(
                    label = "Date received",
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "2026-01-08",
                    isError = text.isNotBlank() && parsed == null,
                    supportingText = if (text.isNotBlank() && parsed == null) {
                        "Use the form 2026-01-08"
                    } else {
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = parsed != null && !isSubmitting,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** `2026-08` from the API becomes `AUGUST 2026` for display. */
private fun formatMonthLabel(isoMonth: String): String =
    runCatching { YearMonth.parse(isoMonth).atDay(1).format(monthHeading) }.getOrDefault(isoMonth)
