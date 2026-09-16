package dev.tapio.hush.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import dev.tapio.hush.core.model.Mix
import dev.tapio.hush.ui.theme.MixPalette
import dev.tapio.hush.ui.theme.PALETTE_ANIMATION_MILLIS
import dev.tapio.hush.ui.theme.mixPalette

/**
 * The palette of [mix], eased into place over 900 ms.
 *
 * Returns a [State] rather than a value so callers can read it inside a draw
 * or layer lambda: an in-flight colour animation then invalidates drawing only
 * and never recomposes the screen. `derivedStateOf` keeps the three underlying
 * animations from leaking into the caller's read set.
 */
@Composable
fun rememberMixPalette(mix: Mix): State<MixPalette> {
    val target = remember(mix) { mixPalette(mix) }
    val spec = remember { tween<androidx.compose.ui.graphics.Color>(PALETTE_ANIMATION_MILLIS) }
    val accent = animateColorAsState(target.accent, spec, label = "accent")
    val glowTop = animateColorAsState(target.glowTop, spec, label = "glowTop")
    val glowBottom = animateColorAsState(target.glowBottom, spec, label = "glowBottom")
    return remember {
        derivedStateOf {
            MixPalette(accent = accent.value, glowTop = glowTop.value, glowBottom = glowBottom.value)
        }
    }
}

/**
 * The night sky: a vertical gradient plus two big radial glows tinted by the
 * mix. Plain `Brush.radialGradient` fills — no blur, no `RenderEffect`, and no
 * per-frame work beyond three `drawRect`s.
 *
 * Each gradient fades to its own colour at zero alpha rather than to
 * [androidx.compose.ui.graphics.Color.Transparent]; transparent *black* would
 * drag a grey halo through the middle of the ramp.
 */
fun Modifier.auroraBackground(palette: State<MixPalette>): Modifier = this.drawWithCache {
    // Read inside the cache block so a palette animation invalidates the draw
    // pass only. Re-runs when the size or the palette changes, not per frame.
    val p = palette.value

    val base = Brush.verticalGradient(
        0f to p.backgroundTop,
        0.55f to p.backgroundBottom,
        1f to p.backgroundTop,
    )
    val topGlow = Brush.radialGradient(
        0f to p.glowTop.copy(alpha = 0.34f),
        0.45f to p.glowTop.copy(alpha = 0.13f),
        1f to p.glowTop.copy(alpha = 0f),
        center = Offset(size.width * 0.92f, size.height * 0.04f),
        radius = size.width * 1.25f,
    )
    val bottomGlow = Brush.radialGradient(
        0f to p.glowBottom.copy(alpha = 0.28f),
        0.45f to p.glowBottom.copy(alpha = 0.10f),
        1f to p.glowBottom.copy(alpha = 0f),
        center = Offset(size.width * 0.08f, size.height * 0.86f),
        radius = size.width * 1.15f,
    )

    onDrawBehind {
        drawRect(base)
        drawRect(topGlow)
        drawRect(bottomGlow)
    }
}
