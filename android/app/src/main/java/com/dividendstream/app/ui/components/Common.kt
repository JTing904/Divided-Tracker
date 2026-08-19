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
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
fun StaleDataBanner(
    cachedAgo: String,
    modifier: Modifier = Modifier,
    /** Why it is stale. Null means the request that would refresh it is still running. */
    reason: AppError? = null,
) {
    // Three different situations, and calling them all "offline" was how a waking server got
    // blamed on the user's signal. The counter keeps running in every one of them, which is
    // the reassurance worth repeating.
    val message = when (reason?.code) {
        null -> "Showing data saved $cachedAgo while we check for newer figures."
        "SERVER_WAKING" ->
            "The server is waking up - showing data saved $cachedAgo. The counter keeps running."
        else ->
            "Offline - showing data saved $cachedAgo. The counter keeps running from the last known figures."
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (reason == null) Icons.Default.CloudSync else Icons.Default.CloudOff,
            contentDescription = null,
            tint = if (reason == null) MaterialTheme.colorScheme.onSurfaceVariant else DividendColors.Warning,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            message,
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

/**
 * The alternative sign-in button.
 *
 * Deliberately outlined rather than filled: there are two ways in and neither is the "real"
 * one, so neither gets the emphasis of a primary button.
 *
 * No Google logo. Their branding rules require their own supplied asset, and an approximation
 * drawn here would be both inaccurate and someone else's trademark.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(54.dp),
        enabled = enabled && !loading,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A labelled rule, for "or" between two equally valid choices. */
@Composable
fun LabelledDivider(label: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline)
    }
}

/**
 * Explains a wait that a spinner would misrepresent.
 *
 * Cached figures cover the screens that only display. Searching for a stock or recording a
 * purchase cannot be served from a cache -- they need a server that is running -- so when the
 * server is still starting, the honest thing is to say so and roughly how long, rather than
 * turn a circle until the person decides the app is broken.
 *
 * The elapsed count is real, taken from when the first request timed out. The estimate beside
 * it is this deployment's measured cold start, and it is called an estimate because it is one.
 */
@Composable
fun ServerWakingBanner(
    elapsedSeconds: Long,
    typicalSeconds: Long,
    modifier: Modifier = Modifier,
) {
    val remaining = (typicalSeconds - elapsedSeconds).coerceAtLeast(0)
    val message = if (remaining > 0) {
        "Waking the server - about ${remaining}s left. It sleeps when unused, and only the " +
            "first request after that has to wait."
    } else {
        "Waking the server - taking longer than usual (${elapsedSeconds}s). It should not be " +
            "much longer."
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.CloudSync,
                contentDescription = null,
                tint = DividendColors.Warning,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { (elapsedSeconds.toFloat() / typicalSeconds).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = DividendColors.Warning,
            trackColor = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * A purchase entered but not yet accepted by the server.
 *
 * Shown beside the holdings rather than added into them. The figures on that screen are the
 * ones the server has confirmed, and a queued purchase that turns out to be refused must never
 * have moved them -- a cost basis that has to be walked back is worse than one that arrives a
 * minute late.
 */
@Composable
fun PendingPurchaseRow(
    companyName: String,
    detail: String,
    failure: String?,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    DsCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(
            1.dp,
            if (failure == null) MaterialTheme.colorScheme.outline else DividendColors.Warning,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (failure == null) Icons.Default.CloudSync else Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = if (failure == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    DividendColors.Warning
                },
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    companyName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    detail,
                    style = MonoFigure,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (failure == null) {
            Text(
                "Waiting to send. It will go through on its own - you can close the app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                failure,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row {
                TextButton(onClick = onRetry) { Text("Try again") }
                TextButton(onClick = onDiscard) { Text("Discard") }
            }
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
