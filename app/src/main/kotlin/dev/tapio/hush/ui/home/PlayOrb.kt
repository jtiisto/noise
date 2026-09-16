package dev.tapio.hush.ui.home

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tapio.hush.ui.theme.HushColor
import dev.tapio.hush.ui.theme.HushSize
import dev.tapio.hush.ui.theme.MixPalette

/** Length of one in-and-out breath. Spec: 4 s, ease-in-out, playing only. */
private const val BREATH_MILLIS = 4_000

/** The visible control is 168 dp inside a 200 dp box, leaving room for the halo. */
private const val ORB_CONTROL_RATIO = 0.84f

/**
 * The play/pause control and the visual centre of the app: three concentric
 * translucent circles tinted by the mix, with a soft halo behind them.
 *
 * This is the only continuous animation in Hush, and it exists only while
 * something is playing — when [isPlaying] is false the `rememberInfiniteTransition`
 * is not composed at all, so no frames are scheduled.
 *
 * Both the palette and the breath phase are read inside `drawBehind`, so an
 * animating orb invalidates drawing without recomposing anything.
 */
@Composable
fun PlayOrb(
    isPlaying: Boolean,
    enabled: Boolean,
    palette: State<MixPalette>,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Diameter of the whole drawing area; the visible control is [ORB_CONTROL_RATIO] of it. */
    diameter: Dp = HushSize.orb,
) {
    val breath: State<Float>? = if (isPlaying) {
        val transition = rememberInfiniteTransition(label = "orbBreath")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(BREATH_MILLIS, easing = EaseInOut),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "breath",
        )
    } else {
        null
    }

    val dim = if (enabled) 1f else 0.45f

    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .clickable(
                onClick = onClick,
                role = Role.Button,
                onClickLabel = contentDescription,
                indication = ripple(bounded = false, radius = diameter / 2),
                interactionSource = null,
            )
            .drawBehind {
                val accent = palette.value.accent
                val b = breath?.value ?: 0f
                val radius = size.minDimension / 2f
                // The control itself is the outer ring; the halo blooms into
                // the remaining space so it is never clipped by the layout.
                val outer = radius * ORB_CONTROL_RATIO * (1f + 0.05f * b)
                val mid = radius * 0.66f
                val inner = radius * 0.50f
                val c = center

                fun a(base: Float) = accent.copy(alpha = base * dim)

                // Halo: reaches past the outer ring and swells with the breath.
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to a(0f),
                        0.52f to a(0.14f + 0.07f * b),
                        0.80f to a(0.09f + 0.05f * b),
                        1f to a(0f),
                        center = c,
                        radius = radius,
                    ),
                    radius = radius,
                    center = c,
                )
                // Ring 1 — the edge of the control, and the one that pulses.
                drawCircle(a(0.18f + 0.18f * b), outer, c, style = Stroke(1.dp.toPx()))
                // Ring 2.
                drawCircle(a(0.06f), mid, c)
                drawCircle(a(0.20f), mid, c, style = Stroke(1.dp.toPx()))
                // Ring 3 — the button face. Lit from the upper left so the orb
                // reads as a sphere rather than a flat disc.
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to a(0.46f),
                        1f to a(0.16f),
                        center = Offset(c.x - inner * 0.40f, c.y - inner * 0.45f),
                        radius = inner * 1.7f,
                    ),
                    radius = inner,
                    center = c,
                )
                drawCircle(a(0.52f), inner, c, style = Stroke(1.5.dp.toPx()))
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            contentDescription = contentDescription,
            tint = if (enabled) HushColor.TextPrimary else HushColor.TextTertiary,
            modifier = Modifier
                // A play triangle looks off-centre when it is geometrically centred.
                .offset(x = if (isPlaying) 0.dp else 3.dp)
                .size(diameter * 0.22f),
        )
    }
}
