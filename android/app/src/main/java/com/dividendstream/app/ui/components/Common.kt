package com.dividendstream.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dividendstream.app.core.AppError
import com.dividendstream.app.ui.theme.DividendColors
import com.dividendstream.app.ui.theme.MonoFigure
import com.dividendstream.app.ui.theme.OverlineLabel
import java.util.Locale

/** The uppercase monospace caption used for every field and section label. */
@Composable
fun OverlineText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(
        text = text.uppercase(Locale.US),
        style = OverlineLabel,
        color = color,
        modifier = modifier,
    )
}

/** The standard dark card the whole design is built from. */
@Composable
fun DsCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    border: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = border,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** A labelled figure, as in the SEC / MIN / HR / DAY row on the dashboard. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    DsCard(modifier = modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
        OverlineText(label)
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style = MonoFigure,
            color = valueColor,
            maxLines = 1,
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        action?.invoke()
    }
}

/** A small rounded status chip: ACCUMULATING, PAID, UPCOMING. */
@Composable
fun StatusPill(status: String, modifier: Modifier = Modifier) {
    val (background, foreground) = when (status.uppercase(Locale.US)) {
        "PAID" -> DividendColors.GrowthDim.copy(alpha = 0.35f) to DividendColors.GrowthBright
        "ACCUMULATING" -> DividendColors.GrowthGlow to DividendColors.Growth
        "PAYABLE" -> DividendColors.Warning.copy(alpha = 0.18f) to DividendColors.Warning
        "CANCELLED" -> DividendColors.Danger.copy(alpha = 0.18f) to DividendColors.Danger
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .background(background, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        OverlineText(status, color = foreground)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Shown when a request failed and there is nothing cached to fall back on. */
@Composable
fun ErrorBanner(
    error: AppError,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    DsCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        if (onRetry != null && error.isRetryable) {
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

/**
 * Shown when the network failed but cached data is on screen. The user is told the figures
 * may be behind rather than being shown stale numbers as if they were current.
 */
@Composable
fun StaleDataBanner(cachedAgo: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CloudOff,
            contentDescription = null,
            tint = DividendColors.Warning,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Offline - showing data saved $cachedAgo. The counter keeps running from the last known figures.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Says a newer release exists, and gets out of the way.
 *
 * Dismissible, and it never blocks anything: the app it appears in works perfectly well, and a
 * notice about a download the user has to go and fetch themselves has no business interrupting
 * them. It states the version rather than urging, because "1.0.2 is out" is a fact and "Update
 * now!" is a demand nobody asked for.
 */
@Composable
fun UpdateAvailableBanner(
    version: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.CloudDownload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Version $version is available to download.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Dismiss",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
    }
}

/** Progress through an accumulation window. */
@Composable
fun AccrualProgressBar(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}
