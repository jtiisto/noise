package dev.jtiisto.noise.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * Dark-only, night-first. There is no light scheme and no dynamic colour: the
 * app is looked at in a dark bedroom and its colour comes from the mix, not
 * from the wallpaper.
 */
private val NightScheme = darkColorScheme(
    primary = MixPalette.Default.accent,
    onPrimary = HushColor.NightTop,
    secondary = MixPalette.Default.glowBottom,
    onSecondary = HushColor.NightTop,
    background = HushColor.NightTop,
    onBackground = HushColor.TextPrimary,
    surface = HushColor.NightDeep,
    onSurface = HushColor.TextPrimary,
    surfaceVariant = HushColor.NightSheet,
    onSurfaceVariant = HushColor.TextSecondary,
    outline = HushColor.HairlineStrong,
    outlineVariant = HushColor.Hairline,
    scrim = HushColor.Scrim,
    error = accentColor(6f, saturation = 0.68f, lightness = 0.66f),
    onError = HushColor.NightTop,
)

@Composable
fun HushTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NightScheme,
        typography = HushTypography,
        shapes = HushShapes,
        content = content,
    )
}
