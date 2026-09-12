package dev.jtiisto.noise.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jtiisto.noise.ui.formatPercent
import dev.jtiisto.noise.ui.theme.HushColor
import dev.jtiisto.noise.ui.theme.HushSize

/**
 * A slim 0..1 volume slider.
 *
 * Hand-rolled rather than Material 3's: the app needs a 4 dp hairline track
 * with a small luminous thumb on a translucent surface, and drawing it
 * directly is both cheaper (one `Canvas`, three primitives) and free of the
 * experimental track/thumb slots. Accessibility is wired up explicitly —
 * TalkBack reads the percentage and can set it.
 *
 * The visible track is thin but the touch target is a full 48 dp row.
 */
@Composable
fun HushSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    accent: Color,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** What TalkBack reads for the current position; a percentage unless overridden. */
    valueDescription: String? = null,
    trackHeight: Dp = 4.dp,
    thumbRadius: Dp = 6.5.dp,
) {
    // pointerInput captures its block once; go through the latest lambda so a
    // drag started before a recomposition still reports to the current owner.
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val enabledNow by rememberUpdatedState(enabled)
    val fraction = value.coerceIn(0f, 1f)

    Box(
        modifier
            .fillMaxWidth()
            .height(HushSize.minTouchTarget)
            .semantics {
                contentDescription = label
                stateDescription = valueDescription ?: formatPercent(fraction)
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { target ->
                    if (!enabledNow) false else {
                        latestOnValueChange(target.coerceIn(0f, 1f)); true
                    }
                }
            }
            .pointerInput(thumbRadius) {
                val thumbPx = thumbRadius.toPx()
                fun emit(x: Float) {
                    if (!enabledNow) return
                    val usable = (size.width - 2f * thumbPx).coerceAtLeast(1f)
                    latestOnValueChange(((x - thumbPx) / usable).coerceIn(0f, 1f))
                }
                detectTapGestures { offset -> emit(offset.x) }
            }
            .pointerInput(thumbRadius) {
                val thumbPx = thumbRadius.toPx()
                fun emit(x: Float) {
                    if (!enabledNow) return
                    val usable = (size.width - 2f * thumbPx).coerceAtLeast(1f)
                    latestOnValueChange(((x - thumbPx) / usable).coerceIn(0f, 1f))
                }
                detectHorizontalDragGestures(
                    onDragStart = { offset -> emit(offset.x) },
                ) { change, _ -> emit(change.position.x) }
            },
    ) {
        val alpha = if (enabled) 1f else 0.38f
        Canvas(Modifier.fillMaxSize()) {
            val thumbPx = thumbRadius.toPx()
            val centreY = size.height / 2f
            val left = thumbPx
            val right = size.width - thumbPx
            val x = left + (right - left) * fraction

            drawLine(
                color = HushColor.SurfaceHigh.copy(alpha = HushColor.SurfaceHigh.alpha * alpha),
                start = Offset(left, centreY),
                end = Offset(right, centreY),
                strokeWidth = trackHeight.toPx(),
                cap = StrokeCap.Round,
            )
            if (fraction > 0f) {
                drawLine(
                    color = accent.copy(alpha = 0.85f * alpha),
                    start = Offset(left, centreY),
                    end = Offset(x, centreY),
                    strokeWidth = trackHeight.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // A soft halo under the thumb ties it to the sound's colour.
            drawCircle(accent.copy(alpha = 0.20f * alpha), thumbPx * 1.75f, Offset(x, centreY))
            drawCircle(HushColor.Foreground.copy(alpha = 0.96f * alpha), thumbPx, Offset(x, centreY))
        }
    }
}
