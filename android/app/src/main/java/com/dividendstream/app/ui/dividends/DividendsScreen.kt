package com.dividendstream.app.ui.dividends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.dividendstream.app.ui.calendar.CalendarScreen
import com.dividendstream.app.ui.calendar.CalendarViewModel
import com.dividendstream.app.ui.dashboard.DashboardScreen
import com.dividendstream.app.ui.dashboard.DashboardViewModel
import com.dividendstream.app.ui.history.HistoryScreen
import com.dividendstream.app.ui.history.HistoryViewModel

/**
 * The three views of one subject.
 *
 * Live, the calendar and the history were three separate tabs, which put three of the app's
 * five top-level destinations on the same topic and left no room for anything that was not
 * about dividends. They are the same data asked three questions -- what is accruing now, when
 * is the next one, what has already arrived -- so they belong behind one destination.
 */
enum class DividendSegment(val label: String) {
    Live("Live"),
    Calendar("Calendar"),
    History("History"),
}

@Composable
fun DividendsScreen(
    segment: DividendSegment,
    onSegmentChange: (DividendSegment) -> Unit,
    dashboardViewModel: DashboardViewModel,
    calendarViewModel: CalendarViewModel,
    historyViewModel: HistoryViewModel,
    userName: String,
    onAddStock: () -> Unit,
    onOpenStock: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        SegmentedRow(
            segment = segment,
            onSegmentChange = onSegmentChange,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )

        when (segment) {
            DividendSegment.Live -> DashboardScreen(
                viewModel = dashboardViewModel,
                userName = userName,
                onAddStock = onAddStock,
                onOpenStock = onOpenStock,
            )

            DividendSegment.Calendar -> CalendarScreen(
                viewModel = calendarViewModel,
                onOpenStock = onOpenStock,
            )

            DividendSegment.History -> HistoryScreen(viewModel = historyViewModel)
        }
    }
}

@Composable
private fun SegmentedRow(
    segment: DividendSegment,
    onSegmentChange: (DividendSegment) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.foundation.layout.Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(4.dp),
    ) {
        DividendSegment.entries.forEach { option ->
            val selected = option == segment
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(
                        if (selected) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onSegmentChange(option) }
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
}
