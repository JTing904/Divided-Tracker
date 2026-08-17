package com.dividendstream.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette from the product design: a near-black canvas so the green figures carry all the
 * visual weight. Green is reserved for money and growth -- it is never used for chrome, so
 * that "the number is green" always means something.
 */
object DividendColors {

    val Canvas = Color(0xFF0A0A0A)
    val Surface = Color(0xFF161616)
    val SurfaceElevated = Color(0xFF1E1E1E)
    val SurfaceSubtle = Color(0xFF222222)
    val Outline = Color(0xFF2A2A2A)

    /** The accent. Used for accruing money, growth and primary actions. */
    val Growth = Color(0xFF34D97B)
    val GrowthBright = Color(0xFF4AE88C)
    val GrowthDim = Color(0xFF1E7A46)

    /** Wash behind the live counter card. */
    val GrowthGlow = Color(0x1A34D97B)

    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFA1A1A1)
    val TextMuted = Color(0xFF6B6B6B)

    val Warning = Color(0xFFF5C451)
    val Danger = Color(0xFFEF5350)

    // Light scheme. The app is dark-first, but a legible light theme costs little and
    // respects a user who has forced light mode at the system level.
    val LightCanvas = Color(0xFFF7F8F7)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceSubtle = Color(0xFFEDEFEE)
    val LightOutline = Color(0xFFDDE1DE)
    val LightGrowth = Color(0xFF12925A)
    val LightTextPrimary = Color(0xFF101410)
    val LightTextSecondary = Color(0xFF55605A)
}
