package com.dividendstream.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = DividendColors.Growth,
    onPrimary = Color(0xFF04240F),
    primaryContainer = DividendColors.GrowthDim,
    onPrimaryContainer = DividendColors.GrowthBright,
    secondary = DividendColors.TextSecondary,
    onSecondary = DividendColors.Canvas,
    background = DividendColors.Canvas,
    onBackground = DividendColors.TextPrimary,
    surface = DividendColors.Surface,
    onSurface = DividendColors.TextPrimary,
    surfaceVariant = DividendColors.SurfaceSubtle,
    onSurfaceVariant = DividendColors.TextSecondary,
    surfaceContainer = DividendColors.SurfaceElevated,
    outline = DividendColors.Outline,
    outlineVariant = DividendColors.Outline,
    error = DividendColors.Danger,
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = DividendColors.LightGrowth,
    onPrimary = Color.White,
    background = DividendColors.LightCanvas,
    onBackground = DividendColors.LightTextPrimary,
    surface = DividendColors.LightSurface,
    onSurface = DividendColors.LightTextPrimary,
    surfaceVariant = DividendColors.LightSurfaceSubtle,
    onSurfaceVariant = DividendColors.LightTextSecondary,
    outline = DividendColors.LightOutline,
    error = DividendColors.Danger,
)

/**
 * Desktop theme. Identical palette to Android, minus the status-bar tinting, which has no
 * desktop equivalent.
 *
 * The caller decides which scheme, from the person's saved preference. The default here is
 * dark only for a caller that expresses no opinion: the design is built around a near-black
 * canvas that makes the green figure the brightest thing on screen.
 */
@Composable
fun DividendStreamTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = DividendTypography,
        content = content,
    )
}
