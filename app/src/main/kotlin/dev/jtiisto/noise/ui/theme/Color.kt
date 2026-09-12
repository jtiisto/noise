package dev.jtiisto.noise.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Fixed (hue-independent) design tokens. Everything that carries a sound's
 * colour lives in [Accent] instead; these are the neutrals the whole app sits
 * on so that a change of mix never moves the reading surface.
 */
object HushColor {
    /** Top of the page gradient — near-black indigo. */
    val NightTop = Color(0xFF0B0F1A)

    /** Bottom of the page gradient — deep navy. */
    val NightDeep = Color(0xFF141A2E)

    /** A touch lighter than [NightDeep]; the opaque ground for modal sheets. */
    val NightSheet = Color(0xFF161D33)

    /** Soft off-white. Never used at full opacity — see the tiers below. */
    val Foreground = Color(0xFFECEBF5)

    // Three alpha tiers keep contrast predictable over the animated aurora.
    val TextPrimary = Foreground.copy(alpha = 0.92f)
    val TextSecondary = Foreground.copy(alpha = 0.64f)
    val TextTertiary = Foreground.copy(alpha = 0.40f)

    /** Card / tile fill: white at low alpha so the aurora reads through it. */
    val SurfaceLow = Color(0x0AFFFFFF)
    val SurfaceMid = Color(0x12FFFFFF)
    val SurfaceHigh = Color(0x1AFFFFFF)

    /** 1 dp separators and unselected borders. */
    val Hairline = Color(0x14FFFFFF)
    val HairlineStrong = Color(0x24FFFFFF)

    /** Scrim behind a modal sheet. */
    val Scrim = Color(0x99050810)
}
