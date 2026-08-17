package com.dividendstream.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

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
 * Dynamic colour is deliberately not used: the green *is* the product's signal for money
 * growing, so it must not be recoloured by the user's wallpaper.
 */
@Composable
fun DividendStreamTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DividendTypography,
        content = content,
    )
}
