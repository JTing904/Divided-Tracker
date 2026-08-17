package com.dividendstream.app.ui.calendar

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dividendstream.app.core.formatDayNumber
import com.dividendstream.app.core.formatDayMonth
import com.dividendstream.app.core.formatMonthAbbrev
import com.dividendstream.app.core.formatMoney
import com.dividendstream.app.data.remote.DividendDto
import com.dividendstream.app.ui.components.DsCard
import com.dividendstream.app.ui.components.EmptyState
import com.dividendstream.app.ui.components.ErrorBanner
import com.dividendstream.app.ui.components.LoadingBox
import com.dividendstream.app.ui.components.OverlineText
import com.dividendstream.app.ui.components.StatTile
import com.dividendstream.app.ui.components.StatusPill
import com.dividendstream.app.ui.theme.MonoFigure
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthHeading = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    onOpenStock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    when {
        state.isLoading && state.months.isEmpty() -> LoadingBox(modifier.fillMaxSize())

        state.error != null && state.months.isEmpty() -> Column(modifier.fillMaxSize().padding(20.dp)) {
            ErrorBanner(error = state.error!!, onRetry = viewModel::refresh)
        }

        state.isEmpty -> EmptyState(
            modifier = modifier.fillMaxSize(),
            title = "Nothing scheduled",
            message = "Upcoming dividend payments for the stocks you own will appear here.",
        )

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        "Expected this month",
                        state.expectedThisMonth.formatMoney(state.currency),
                        Modifier.weight(1f),
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                    StatTile("Upcoming events", state.eventCount.toString(), Modifier.weight(1f))
                }
            }

            state.months.forEach { month ->
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OverlineText(month.yearMonth.atDay(1).format(monthHeading))
                        Text(
                            month.total.formatMoney(state.currency),
                            style = MonoFigure,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                items(month.items, key = { it.id }) { dividend ->
                    CalendarEventCard(dividend, onClick = { onOpenStock(dividend.symbol) })
                }
            }
        }
    }
}

@Composable
private fun CalendarEventCard(dividend: DividendDto, onClick: () -> Unit) {
    DsCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateBadge(
                month = dividend.paymentDate.formatMonthAbbrev(),
                day = dividend.paymentDate.formatDayNumber(),
            )

            Spacer(Modifier.width(14.dp))

            // Everything except the date badge shares one column, so the dates and the
            // status pill get the full width instead of competing with the amount.
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        dividend.companyName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        dividend.expectedAmount.formatMoney(dividend.currency),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        dividend.exDate?.let { append("Ex ${it.formatDayMonth()}  ·  ") }
                        append("Pays ${dividend.paymentDate.formatDayMonth()}")
                    },
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                StatusPill(dividend.status)
            }
        }
    }
}

@Composable
private fun DateBadge(month: String, day: String) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                month,
                style = MonoFigure.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                day,
                style = MonoFigure,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
        }
    }
}
