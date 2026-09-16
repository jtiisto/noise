package dev.tapio.hush.ui.critterscenes

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tapio.hush.ui.critter.CritterKind
import dev.tapio.hush.ui.critter.pulse
import dev.tapio.hush.ui.theme.MixPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

private const val TAU = (2.0 * PI).toFloat()
private const val PIf = PI.toFloat()

/**
 * The approved "storybook" composition for each sound. A bolder take on the
 * shipped [dev.tapio.hush.ui.critter.Critter]: the same established character
 * ([CritterKind]) floating in a small trimmed scene that matches its sound, over
 * the app's aurora ground — no opaque panels or water blocks.
 *
 * **Decorative only.** There is no `clickable`/`pointerInput`/`toggleable`
 * anywhere here, and it is a bare [Canvas]; Compose only routes touches to
 * composables that carry a pointer modifier, so when this is drawn as a sibling
 * *above* the play orb it shows on top yet never steals the orb's taps.
 *
 * **Motion budget.** One [rememberInfiniteTransition] drives two looping values,
 * both read *inside* the draw lambda so an animating scene invalidates drawing
 * without recomposing:
 *  - **breathe** — a slow 0..1 triangle (ease-in-out, reversing) that becomes the
 *    gentle body bob/breathe and the firefly's belly glow.
 *  - **clock** — a 0..1 sawtooth (linear, restarting) that every secondary motion
 *    is *derived* from with pure math: blinks/twitches are Hann-window [pulse]s of
 *    it, rain and embers and ripples advance with it, waves and leaves drift with
 *    it. No coroutines, timers or `delay`; nothing allocates per frame (Paths are
 *    reused, colours are the [Color] value class).
 * Everything is a touch livelier while [isPlaying] and calmer while paused.
 *
 * @param variant picks an alternate composition where one exists; the default is
 *   the approved one per kind — frog *umbrella* (0), duck *bottoms-up* (1).
 * @param palette read only inside the draw lambda, so a mix-colour transition
 *   never recomposes the scene.
 */
@Composable
fun CritterScene(
    kind: CritterKind,
    isPlaying: Boolean,
    palette: State<MixPalette>,
    modifier: Modifier = Modifier,
    variant: Int = if (kind == CritterKind.DUCK) 1 else 0,
    size: Dp = 138.dp,
) {
    val transition = rememberInfiniteTransition(label = "critterScene")
    val breathe = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 3_200 else 4_600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sceneBreathe",
    )
    // Restart (not reverse), so derived motions drift one way and repeat: rain
    // falls and a new streak follows, ripples expand outward, blinks happen once
    // per turn of the loop.
    val clock = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 7_000 else 10_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sceneClock",
    )
    val paths = remember { ScenePaths() }
    Canvas(modifier.size(size)) {
        drawScene(kind, variant, breathe.value, clock.value, isPlaying, palette.value.accent, paths)
    }
}

/**
 * A scene pinned to explicit animation values — no live transition.
 *
 * `internal`, used only by the screenshot references: a still render captures one
 * instant, so this lets a `@PreviewTest` freeze [breathe]/[clock] at a chosen
 * frame (a mid-blink frog, a small vs large duck ripple) the live [CritterScene]
 * would only pass through.
 */
@Composable
internal fun CritterSceneFrame(
    kind: CritterKind,
    breathe: Float,
    clock: Float,
    isPlaying: Boolean,
    palette: State<MixPalette>,
    modifier: Modifier = Modifier,
    variant: Int = if (kind == CritterKind.DUCK) 1 else 0,
    size: Dp = 138.dp,
) {
    val paths = remember { ScenePaths() }
    Canvas(modifier.size(size)) {
        drawScene(kind, variant, breathe, clock, isPlaying, palette.value.accent, paths)
    }
}

/**
 * A tiny fixed pool of [Path]s, remembered once and reset between shapes, so a
 * whole animated frame allocates no paths. [main] holds the shape being built and
 * drawn; [a]/[b] are the two operands a boolean [Path.op] (the crescent moon)
 * needs to exist at the same time as the result in [main].
 */
private class ScenePaths {
    val main = Path()
    val a = Path()
    val b = Path()
}

/**
 * Draws [kind]'s scene into the current square canvas at animation phase
 * [breathe]/[clock]. All colours, helpers and per-scene art live here on purpose:
 * this package is Kover-excluded exploration/art, drawing-only, so it carries no
 * counted logic and needs no unit tests.
 */
private fun DrawScope.drawScene(
    kind: CritterKind,
    variant: Int,
    breathe: Float,
    clock: Float,
    playing: Boolean,
    accent: Color,
    paths: ScenePaths,
) {
    val s = size.minDimension
    fun x(f: Float) = f * s
    fun y(f: Float) = f * s
    val live = if (playing) 1f else 0.55f
    val swing = (breathe - 0.5f) * 2f // -1..1

    // --- Palette (matches the shipped Critter art, then extends it) ----------
    val catBody = Color(0xFFB9B3DA)
    val catDark = Color(0xFF938CC0)
    val catEar = Color(0xFFE3A9C6)
    val frogBody = Color(0xFF83CE84)
    val frogDark = Color(0xFF5CAE64)
    val frogBelly = Color(0xFFD3ECAF)
    val whaleBody = Color(0xFF7DB0E8)
    val whaleDark = Color(0xFF5B8ED2)
    val whaleBelly = Color(0xFFDCEBFB)
    val foxBody = Color(0xFFEB9A57)
    val foxDark = Color(0xFFD07C3D)
    val foxCream = Color(0xFFF8E7D1)
    val duckBody = Color(0xFFF3D268)
    val duckDark = Color(0xFFE3BC4C)
    val duckBeak = Color(0xFFEE9A46)
    val birdBelly = Color(0xFFF4ECD4)
    val fireflyBody = Color(0xFF423E57)
    val fireflyGlow = Color(0xFFEBF69C)
    val fireflyWing = Color(0xFFDCE4F2)
    val eyeColor = Color(0xFF2A2740)
    val shine = Color(0xFFFFFFFF)
    val blush = Color(0xFFF2A6B4)

    val moonlight = Color(0xFFF3EFD8)
    val starColor = Color(0xFFDCE4F6)
    val leafLight = Color(0xFF74C079)
    val leafDark = Color(0xFF4C9A57)
    val stemColor = Color(0xFF6E9A52)
    val rainColor = Color(0xFFCFE0F5)
    val waterLight = Color(0xFF6FA6DE)
    val waterMid = Color(0xFF4E86C4)
    val foam = Color(0xFFE6F1FC)
    val logColor = Color(0xFF7C5A40)
    val logEnd = Color(0xFFB08A63)
    val flameOuter = Color(0xFFF07A3C)
    val flameMid = Color(0xFFF6A83E)
    val flameCore = Color(0xFFFBE07A)
    val ember = Color(0xFFFBC46A)
    val grassLight = Color(0xFF5FA45C)
    val grassDark = Color(0xFF3E7A46)
    val petal = Color(0xFFE8A6D0)
    val petalCore = Color(0xFFF6E08A)
    val cushionColor = Color(0xFF7C6E9C)
    val cushionHi = Color(0xFF9A8CBC)
    val cattail = Color(0xFF8A6A44)
    val fishBody = Color(0xFFEE9A6E)
    val fishBelly = Color(0xFFF7D3B6)

    // --- Shared primitives ---------------------------------------------------
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) =
        drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2))

    fun tri(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, color: Color) {
        val p = paths.main
        p.reset()
        p.moveTo(ax, ay); p.lineTo(bx, by); p.lineTo(cx, cy); p.close()
        drawPath(p, color)
    }

    fun softGlow(cx: Float, cy: Float, rad: Float, color: Color, a0: Float) {
        if (a0 <= 0f || rad <= 0f) return
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = a0),
                0.5f to color.copy(alpha = a0 * 0.42f),
                1f to color.copy(alpha = 0f),
                center = Offset(cx, cy),
                radius = rad,
            ),
            radius = rad,
            center = Offset(cx, cy),
        )
    }

    fun crescent(cx: Float, cy: Float, r: Float, color: Color) {
        val moon = paths.a
        moon.reset()
        moon.addOval(Rect(cx - r, cy - r, cx + r, cy + r))
        val cut = paths.b
        cut.reset()
        cut.addOval(Rect(cx - r + r * 0.56f, cy - r - r * 0.30f, cx + r + r * 0.56f, cy + r - r * 0.30f))
        val out = paths.main
        out.reset()
        out.op(moon, cut, PathOperation.Difference)
        drawPath(out, color)
    }

    fun sparkle(cx: Float, cy: Float, r: Float, color: Color) {
        val p = paths.main
        p.reset()
        val inner = r * 0.34f
        for (k in 0 until 8) {
            val ang = -PIf / 2f + k * (PIf / 4f)
            val rad = if (k % 2 == 0) r else inner
            val px = cx + rad * cos(ang)
            val py = cy + rad * sin(ang)
            if (k == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
        drawPath(p, color)
    }

    fun beadEye(cx: Float, cy: Float, r: Float) {
        drawCircle(eyeColor, r, Offset(cx, cy))
        drawCircle(shine, r * 0.36f, Offset(cx - r * 0.28f, cy - r * 0.34f))
    }

    fun closedEye(cx: Float, cy: Float, w: Float) {
        drawArc(
            color = eyeColor,
            startAngle = 22f, sweepAngle = 136f, useCenter = false,
            topLeft = Offset(cx - w / 2, cy - w / 2), size = Size(w, w),
            style = Stroke(width = w * 0.15f, cap = StrokeCap.Round),
        )
    }

    // A bead eye that blinks: a round shiny bead at [closed]~0, squashing flat and
    // losing its catch-light as it shuts, then a soft happy arc once nearly closed.
    fun blinkingEye(cx: Float, cy: Float, r: Float, closed: Float) {
        val open = 1f - closed
        if (open > 0.15f) {
            oval(cx, cy, r, r * open, eyeColor)
            val sh = ((open - 0.4f) / 0.6f).coerceIn(0f, 1f)
            if (sh > 0f) drawCircle(shine.copy(alpha = sh), r * 0.34f, Offset(cx - r * 0.28f, cy - r * 0.30f))
        } else {
            closedEye(cx, cy, r * 2.6f)
        }
    }

    fun smile(cx: Float, cy: Float, w: Float, h: Float, color: Color, stroke: Float) {
        drawArc(
            color = color,
            startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(cx - w / 2, cy - h / 2), size = Size(w, h),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }

    fun leaf(cx: Float, cy: Float, halfLen: Float, halfW: Float, rot: Float, fill: Color, vein: Color) {
        withTransform({ rotate(rot, pivot = Offset(cx, cy)) }) {
            val p = paths.main
            p.reset()
            p.moveTo(cx - halfLen, cy)
            p.quadraticTo(cx, cy - halfW, cx + halfLen, cy)
            p.quadraticTo(cx, cy + halfW, cx - halfLen, cy)
            p.close()
            drawPath(p, fill)
            drawLine(vein, Offset(cx - halfLen * 0.82f, cy), Offset(cx + halfLen * 0.9f, cy), s * 0.006f, StrokeCap.Round)
            for (j in 1..3) {
                val t = cx - halfLen * 0.4f + j * halfLen * 0.32f
                drawLine(vein, Offset(t, cy), Offset(t + halfLen * 0.12f, cy - halfW * 0.42f), s * 0.005f, StrokeCap.Round)
                drawLine(vein, Offset(t, cy), Offset(t + halfLen * 0.12f, cy + halfW * 0.42f), s * 0.005f, StrokeCap.Round)
            }
        }
    }

    // --- Character bodies (matched to the shipped critters) ------------------
    fun catCurled(cx: Float, base: Float, r: Float) {
        val cs = r * 2.2f
        val ox = cx - 0.52f * cs
        val oy = base - 0.96f * cs
        fun px(f: Float) = ox + f * cs
        fun py(f: Float) = oy + f * cs
        oval(px(0.52f), py(0.70f), 0.40f * cs, 0.26f * cs, catBody)
        drawArc(
            color = catDark,
            startAngle = 40f, sweepAngle = 200f, useCenter = false,
            topLeft = Offset(px(0.16f), py(0.52f)), size = Size(0.62f * cs, 0.42f * cs),
            style = Stroke(width = cs * 0.11f, cap = StrokeCap.Round),
        )
        val hx = px(0.35f); val hy = py(0.62f); val hr = 0.205f * cs
        drawCircle(catBody, hr, Offset(hx, hy))
        tri(hx - hr * 0.86f, hy - hr * 0.62f, hx - hr * 0.30f, hy - hr * 1.28f, hx - hr * 0.02f, hy - hr * 0.74f, catBody)
        tri(hx + hr * 0.10f, hy - hr * 0.78f, hx + hr * 0.44f, hy - hr * 1.30f, hx + hr * 0.78f, hy - hr * 0.66f, catBody)
        tri(hx - hr * 0.62f, hy - hr * 0.66f, hx - hr * 0.30f, hy - hr * 1.02f, hx - hr * 0.14f, hy - hr * 0.70f, catEar)
        tri(hx + hr * 0.24f, hy - hr * 0.70f, hx + hr * 0.46f, hy - hr * 1.04f, hx + hr * 0.66f, hy - hr * 0.64f, catEar)
        closedEye(hx - hr * 0.36f, hy + hr * 0.02f, hr * 0.62f)
        closedEye(hx + hr * 0.42f, hy + hr * 0.02f, hr * 0.62f)
        tri(hx + hr * 0.00f, hy + hr * 0.30f, hx + hr * 0.14f, hy + hr * 0.30f, hx + hr * 0.07f, hy + hr * 0.44f, catEar)
        drawCircle(blush.copy(alpha = 0.55f), hr * 0.22f, Offset(hx - hr * 0.02f, hy + hr * 0.36f))
        oval(px(0.30f), py(0.86f), 0.16f * cs, 0.09f * cs, catDark)
    }

    fun foxCurled(cx: Float, base: Float, r: Float) {
        oval(cx + r * 0.10f, base - r * 0.30f, r * 1.02f, r * 0.60f, foxBody)
        drawArc(
            color = foxBody,
            startAngle = 28f, sweepAngle = 214f, useCenter = false,
            topLeft = Offset(cx - r * 1.0f, base - r * 0.72f),
            size = Size(r * 1.74f, r * 1.14f),
            style = Stroke(width = r * 0.36f, cap = StrokeCap.Round),
        )
        drawCircle(foxCream, r * 0.22f, Offset(cx - r * 0.78f, base + r * 0.06f))
        val hx = cx - r * 0.44f; val hy = base - r * 0.22f; val hr = r * 0.52f
        drawCircle(foxBody, hr, Offset(hx, hy))
        tri(hx - hr * 0.92f, hy - hr * 0.50f, hx - hr * 0.58f, hy - hr * 1.42f, hx - hr * 0.06f, hy - hr * 0.72f, foxBody)
        tri(hx - hr * 0.70f, hy - hr * 0.56f, hx - hr * 0.52f, hy - hr * 1.06f, hx - hr * 0.22f, hy - hr * 0.68f, foxDark)
        tri(hx + hr * 0.08f, hy - hr * 0.74f, hx + hr * 0.60f, hy - hr * 1.42f, hx + hr * 0.92f, hy - hr * 0.50f, foxBody)
        tri(hx + hr * 0.24f, hy - hr * 0.68f, hx + hr * 0.52f, hy - hr * 1.06f, hx + hr * 0.70f, hy - hr * 0.56f, foxDark)
        oval(hx, hy + hr * 0.34f, hr * 0.74f, hr * 0.58f, foxCream)
        closedEye(hx - hr * 0.40f, hy - hr * 0.02f, hr * 0.54f)
        closedEye(hx + hr * 0.40f, hy - hr * 0.02f, hr * 0.54f)
        drawCircle(eyeColor, hr * 0.13f, Offset(hx, hy + hr * 0.40f))
    }

    // --- The scenes ----------------------------------------------------------

    /**
     * DEFAULT — a sleeping cat on a cushion under a free crescent moon and a
     * couple of stars (no window panel), a trail of accent "z"s drifting up.
     */
    fun sceneCatNook() {
        softGlow(x(0.52f), y(0.30f), s * 0.5f, accent, 0.15f)
        // Moon + a couple of stars, floating free. One star twinkles slowly.
        crescent(x(0.70f), y(0.20f), s * 0.058f, moonlight)
        val tw = 0.5f + 0.5f * pulse(clock, 0.35f, 0.5f)
        sparkle(x(0.44f), y(0.17f), s * 0.020f, starColor.copy(alpha = 0.85f * tw))
        sparkle(x(0.54f), y(0.30f), s * 0.014f, starColor.copy(alpha = 0.6f))
        sparkle(x(0.82f), y(0.34f), s * 0.016f, starColor.copy(alpha = 0.7f))
        // Cushion.
        oval(x(0.5f), y(0.90f), x(0.38f), y(0.095f), cushionColor)
        oval(x(0.5f), y(0.875f), x(0.35f), y(0.07f), cushionHi)
        drawCircle(cushionColor, s * 0.016f, Offset(x(0.5f), y(0.87f)))
        // Cat, breathing gently from its base.
        val bob = -swing * s * 0.008f * live
        val breatheScale = 1f + 0.03f * breathe * live
        withTransform({
            translate(0f, bob)
            scale(breatheScale, breatheScale, pivot = Offset(x(0.5f), y(0.94f)))
        }) {
            catCurled(x(0.5f), y(0.90f), s * 0.21f)
        }
        // A staggered trail of three "z"s rising and fading, driven by the clock.
        val stagger = 1f / 3f
        for (i in 0..2) {
            val f = clock - i * stagger
            val lp = f - floor(f)
            val a = 0.85f * sin(PIf * lp)
            if (a <= 0.02f) continue
            val zx = x(0.62f) + s * 0.13f * lp
            val zy = y(0.60f) - s * 0.30f * lp
            val zw = s * (0.05f + 0.045f * lp)
            val st = zw * 0.2f
            val c = accent.copy(alpha = a)
            drawLine(c, Offset(zx, zy), Offset(zx + zw, zy), st, StrokeCap.Round)
            drawLine(c, Offset(zx + zw, zy), Offset(zx, zy + zw), st, StrokeCap.Round)
            drawLine(c, Offset(zx, zy + zw), Offset(zx + zw, zy + zw), st, StrokeCap.Round)
        }
    }

    /**
     * RAIN / THUNDERSTORM — the frog under a leaf umbrella: rain streaks fall, the
     * umbrella sways, an occasional lightning glimmer, a slow blink and throat
     * pulse. Already floats free over the aurora.
     */
    fun sceneRainFrog() {
        // Lightning glimmer: a narrow pulse brightens the soft glow behind.
        val flash = pulse(clock, 0.12f, 0.045f) * live
        softGlow(x(0.34f), y(0.26f), s * 0.5f, accent, 0.22f + 0.45f * flash)
        softGlow(x(0.5f), y(0.5f), s * 0.6f, accent, 0.07f + 0.3f * flash)
        // Rain streaks, falling and wrapping on the clock.
        for (i in 0 until 16) {
            val fx = (i * 0.147f + 0.02f) % 1f
            val phase = (i * 0.31f + clock * 1.3f) % 1f
            val rx = x(fx)
            val ry = y(0.02f + phase * 0.86f)
            val len = s * (0.06f + (i % 3) * 0.02f)
            val a = (0.30f + (i % 4) * 0.06f) * (1f - 0.25f * phase)
            drawLine(rainColor.copy(alpha = a), Offset(rx, ry), Offset(rx - s * 0.03f, ry + len), s * 0.008f, StrokeCap.Round)
        }
        // Puddle ripple.
        oval(x(0.5f), y(0.955f), x(0.32f), y(0.026f), waterMid.copy(alpha = 0.45f))
        drawArc(
            color = rainColor.copy(alpha = 0.4f),
            startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(x(0.36f), y(0.93f)), size = Size(x(0.14f), y(0.05f)),
            style = Stroke(width = s * 0.006f, cap = StrokeCap.Round),
        )
        val frogCx = x(0.5f); val frogBase = y(0.9f); val fr = s * 0.2f
        // Umbrella / tent, swaying a few degrees around the frog's grip.
        val sway = swing * 4f * live
        if (variant == 1) {
            withTransform({ rotate(sway * 0.6f, pivot = Offset(x(0.5f), y(0.62f))) }) {
                leaf(x(0.5f), y(0.4f), s * 0.42f, s * 0.30f, -18f, leafLight, leafDark)
                leaf(x(0.5f), y(0.4f), s * 0.42f, s * 0.30f, -18f, Color(0x22FFFFFF), leafDark.copy(alpha = 0f))
            }
        } else {
            withTransform({ rotate(sway, pivot = Offset(x(0.66f), y(0.62f))) }) {
                drawLine(stemColor, Offset(x(0.60f), y(0.28f)), Offset(x(0.66f), y(0.62f)), s * 0.018f, StrokeCap.Round)
                leaf(x(0.46f), y(0.24f), s * 0.40f, s * 0.24f, -12f, leafLight, leafDark)
                drawCircle(rainColor.copy(alpha = 0.7f), s * 0.012f, Offset(x(0.09f), y(0.34f)))
                drawCircle(rainColor.copy(alpha = 0.5f), s * 0.01f, Offset(x(0.86f), y(0.30f)))
            }
        }
        // Frog, bobbing gently, with a throat pulse and an occasional blink.
        val bob = -swing * s * 0.01f * live
        withTransform({ translate(0f, bob) }) {
            oval(frogCx - fr * 0.9f, frogBase, fr * 0.5f, fr * 0.28f, frogDark)
            oval(frogCx + fr * 0.9f, frogBase, fr * 0.5f, fr * 0.28f, frogDark)
            oval(frogCx, frogBase - fr * 0.5f, fr * 1.32f, fr * 1.06f, frogBody)
            val throat = 1f + 0.06f * live * breathe
            oval(frogCx, frogBase - fr * 0.18f, fr * 0.78f, fr * 0.66f * throat, frogBelly)
            val er = fr * 0.5f
            drawCircle(frogBody, er, Offset(frogCx - fr * 0.56f, frogBase - fr * 1.42f))
            drawCircle(frogBody, er, Offset(frogCx + fr * 0.56f, frogBase - fr * 1.42f))
            drawCircle(shine, er * 0.72f, Offset(frogCx - fr * 0.56f, frogBase - fr * 1.4f))
            drawCircle(shine, er * 0.72f, Offset(frogCx + fr * 0.56f, frogBase - fr * 1.4f))
            val closed = pulse(clock, 0.55f, 0.06f)
            blinkingEye(frogCx - fr * 0.52f, frogBase - fr * 1.36f, er * 0.42f, closed)
            blinkingEye(frogCx + fr * 0.6f, frogBase - fr * 1.36f, er * 0.42f, closed)
            smile(frogCx, frogBase - fr * 0.7f, fr * 1.3f, fr * 0.95f, frogDark, s * 0.014f)
            drawCircle(blush.copy(alpha = 0.5f), fr * 0.2f, Offset(frogCx - fr * 0.95f, frogBase - fr * 0.55f))
            drawCircle(blush.copy(alpha = 0.5f), fr * 0.2f, Offset(frogCx + fr * 0.95f, frogBase - fr * 0.55f))
            if (variant != 1) {
                oval(frogCx + fr * 0.8f, frogBase - fr * 1.1f, fr * 0.2f, fr * 0.16f, frogDark)
            }
        }
    }

    /**
     * OCEAN — the whale bobbing on the swell among light translucent wave curves
     * (no filled sea), the spout puffing, a crescent moon and stars.
     */
    fun sceneOceanWhale() {
        softGlow(x(0.5f), y(0.34f), s * 0.55f, accent, 0.18f)
        crescent(x(0.78f), y(0.18f), s * 0.06f, moonlight)
        sparkle(x(0.2f), y(0.16f), s * 0.02f, starColor.copy(alpha = 0.85f))
        sparkle(x(0.32f), y(0.26f), s * 0.015f, starColor.copy(alpha = 0.7f))
        sparkle(x(0.64f), y(0.12f), s * 0.017f, starColor.copy(alpha = 0.8f))

        // Waves: light translucent curves that drift sideways on the clock.
        fun wave(topF: Float, amp: Float, seg: Float, phase: Float, color: Color, stroke: Float) {
            val p = paths.main
            p.reset()
            val top = y(topF)
            var xx = -seg * (1f + phase)
            p.moveTo(xx, top)
            var up = true
            while (xx < s + seg) {
                val nx = xx + seg
                p.quadraticTo(xx + seg / 2f, top + (if (up) -amp else amp), nx, top)
                xx = nx; up = !up
            }
            drawPath(p, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
        wave(0.66f, s * 0.03f, s * 0.26f, clock, waterMid.copy(alpha = 0.5f), s * 0.012f)

        // Whale bobbing/tilting on the swell.
        val whaleBob = -swing * s * 0.02f * live
        val whaleTilt = -24f + swing * 3f * live
        withTransform({
            translate(0f, whaleBob)
            rotate(whaleTilt, pivot = Offset(x(0.5f), y(0.62f)))
        }) {
            val fl = paths.main
            fl.reset()
            fl.moveTo(x(0.66f), y(0.52f))
            fl.cubicTo(x(0.78f), y(0.40f), x(0.93f), y(0.40f), x(0.93f), y(0.49f))
            fl.cubicTo(x(0.88f), y(0.53f), x(0.80f), y(0.55f), x(0.72f), y(0.57f))
            fl.cubicTo(x(0.82f), y(0.59f), x(0.92f), y(0.64f), x(0.90f), y(0.72f))
            fl.cubicTo(x(0.82f), y(0.67f), x(0.74f), y(0.61f), x(0.66f), y(0.57f))
            fl.close()
            drawPath(fl, whaleDark)
            oval(x(0.5f), y(0.56f), s * 0.24f, s * 0.16f, whaleBody)
            oval(x(0.42f), y(0.62f), s * 0.16f, s * 0.09f, whaleBelly)
            oval(x(0.44f), y(0.68f), s * 0.06f, s * 0.035f, whaleDark)
            smile(x(0.34f), y(0.6f), s * 0.14f, s * 0.09f, whaleDark, s * 0.012f)
            beadEye(x(0.33f), y(0.53f), s * 0.028f)
        }
        // Spout — a steady base with a droplet cluster rising and fading.
        val bx = x(0.44f); val by = y(0.32f)
        drawLine(foam.copy(alpha = 0.8f), Offset(bx, by + s * 0.06f), Offset(bx, by - s * 0.02f), s * 0.02f, StrokeCap.Round)
        for (k in -2..2) {
            val ang = k * 0.28f
            val dx = sin(ang) * s * 0.12f
            val dy = -cos(ang) * s * 0.12f
            drawCircle(foam.copy(alpha = 0.8f - abs(k) * 0.14f), s * (0.024f - abs(k) * 0.003f), Offset(bx + dx, by + dy))
        }
        val pa = sin(PIf * clock)
        val ry = by - s * (0.06f + 0.16f * clock)
        drawCircle(foam.copy(alpha = pa * 0.9f), s * 0.03f, Offset(bx, ry))
        drawCircle(foam.copy(alpha = pa * 0.7f), s * 0.026f, Offset(bx - s * 0.09f, ry + s * 0.05f))
        drawCircle(foam.copy(alpha = pa * 0.7f), s * 0.026f, Offset(bx + s * 0.09f, ry + s * 0.05f))

        // A couple of foreground wave curves in front of the whale's lower body.
        wave(0.78f, s * 0.04f, s * 0.30f, (clock + 0.5f) % 1f, waterLight.copy(alpha = 0.45f), s * 0.011f)
        wave(0.9f, s * 0.03f, s * 0.24f, (clock + 0.25f) % 1f, waterMid.copy(alpha = 0.4f), s * 0.01f)
    }

    /**
     * CAMPFIRE — the fox curled asleep beside a small crossed-log fire that
     * flickers softly, embers drifting up, a warm glow and a soft grounding shadow
     * (no opaque ground block).
     */
    fun sceneCampfireFox() {
        val flick = 0.5f + 0.5f * sin(TAU * clock * 2f) * live
        softGlow(x(0.34f), y(0.62f), s * 0.62f, Color(0xFFF7A54A), 0.30f + 0.08f * flick + 0.04f * breathe)
        softGlow(x(0.34f), y(0.6f), s * 0.3f, accent, 0.12f)
        sparkle(x(0.8f), y(0.16f), s * 0.018f, starColor.copy(alpha = 0.7f))
        sparkle(x(0.68f), y(0.24f), s * 0.014f, starColor.copy(alpha = 0.6f))
        // Soft grounding shadow (translucent, not an opaque rectangle).
        oval(x(0.5f), y(0.93f), x(0.42f), y(0.05f), Color(0xFF0A0E1A).copy(alpha = 0.32f))

        // Fox curled, to the right, breathing gently.
        val foxBob = -swing * s * 0.007f * live
        withTransform({ translate(0f, foxBob) }) {
            foxCurled(x(0.66f), y(0.82f), s * 0.2f)
        }

        // Campfire, to the left.
        val fx = x(0.32f); val fbase = y(0.84f)
        withTransform({ rotate(18f, pivot = Offset(fx, fbase)) }) {
            drawRoundRect(logColor, topLeft = Offset(fx - s * 0.16f, fbase - s * 0.03f), size = Size(s * 0.32f, s * 0.055f), cornerRadius = CornerRadius(s * 0.03f, s * 0.03f))
            drawCircle(logEnd, s * 0.026f, Offset(fx + s * 0.15f, fbase))
        }
        withTransform({ rotate(-16f, pivot = Offset(fx, fbase)) }) {
            drawRoundRect(logColor, topLeft = Offset(fx - s * 0.16f, fbase - s * 0.03f), size = Size(s * 0.32f, s * 0.055f), cornerRadius = CornerRadius(s * 0.03f, s * 0.03f))
            drawCircle(logEnd, s * 0.026f, Offset(fx - s * 0.15f, fbase))
        }
        fun flame(cx: Float, tipY: Float, baseY: Float, halfW: Float, color: Color) {
            val p = paths.main
            p.reset()
            p.moveTo(cx, tipY)
            p.cubicTo(cx + halfW, tipY + (baseY - tipY) * 0.5f, cx + halfW, baseY, cx, baseY)
            p.cubicTo(cx - halfW, baseY, cx - halfW, tipY + (baseY - tipY) * 0.5f, cx, tipY)
            p.close()
            drawPath(p, color)
        }
        // Flames flicker a touch taller/brighter with the clock.
        val h = 1f + 0.12f * flick
        flame(fx, fbase - s * 0.28f * h, fbase - s * 0.02f, s * 0.11f, flameOuter)
        flame(fx + s * 0.02f, fbase - s * 0.2f * h, fbase - s * 0.02f, s * 0.075f, flameMid)
        flame(fx, fbase - s * 0.12f * h, fbase - s * 0.01f, s * 0.04f, flameCore.copy(alpha = 0.85f + 0.15f * flick))
        // Embers drifting up on the clock.
        for (k in 0 until 6) {
            val t = (k * 0.17f + clock) % 1f
            val ex = fx + sin(k * 1.7f + clock * TAU * 0.3f) * s * 0.1f
            val ey = fbase - s * 0.3f - t * s * 0.42f
            val ea = (0.9f - t * 0.85f).coerceAtLeast(0f)
            drawCircle(ember.copy(alpha = ea), s * (0.012f - t * 0.007f).coerceAtLeast(0.003f), Offset(ex, ey))
        }
    }

    /**
     * WIND — the bird perched on a branch that sways in the gust, fluttering a
     * wing and blinking; leaves and faint gust lines blow past. Floats free.
     */
    fun sceneWindBird() {
        softGlow(x(0.5f), y(0.4f), s * 0.55f, accent, 0.16f)
        val body = lerp(accent, Color.White, 0.4f)
        val wing = lerp(accent, Color.Black, 0.12f)
        // Gust lines drifting right and fading in and out.
        for (k in 0 until 3) {
            val ph = (clock + k * 0.33f) % 1f
            val gx = x(0.02f) + ph * s * 0.5f
            val a = sin(PIf * ph) * (0.12f + k * 0.02f)
            drawArc(
                color = Color.White.copy(alpha = a),
                startAngle = 200f, sweepAngle = 130f, useCenter = false,
                topLeft = Offset(gx, y(0.22f + k * 0.12f)), size = Size(x(0.42f), y(0.1f)),
                style = Stroke(width = s * 0.008f, cap = StrokeCap.Round),
            )
        }
        // Branch + bird sway together, rooted at the branch base.
        val sway = swing * 3f * live
        withTransform({ rotate(sway, pivot = Offset(x(0.12f), y(1.0f))) }) {
            val branch = paths.main
            branch.reset()
            branch.moveTo(x(0.12f), y(1.0f))
            branch.cubicTo(x(0.26f), y(0.78f), x(0.44f), y(0.72f), x(0.74f), y(0.66f))
            drawPath(branch, stemColor, style = Stroke(width = s * 0.028f, cap = StrokeCap.Round))
            leaf(x(0.6f), y(0.62f), s * 0.09f, s * 0.05f, -28f, leafLight, leafDark)
            leaf(x(0.7f), y(0.68f), s * 0.08f, s * 0.045f, -8f, leafLight, leafDark)

            val bx = x(0.5f); val by = y(0.5f); val br = s * 0.16f
            drawLine(Color(0xFFF2B457), Offset(bx - s * 0.04f, by + br * 0.9f), Offset(bx - s * 0.04f, y(0.68f)), s * 0.012f, StrokeCap.Round)
            drawLine(Color(0xFFF2B457), Offset(bx + s * 0.04f, by + br * 0.9f), Offset(bx + s * 0.04f, y(0.69f)), s * 0.012f, StrokeCap.Round)
            tri(bx - br * 1.5f, by - br * 0.1f, bx - br * 0.6f, by - br * 0.4f, bx - br * 0.7f, by + br * 0.5f, wing)
            drawCircle(body, br, Offset(bx, by))
            oval(bx + br * 0.1f, by + br * 0.5f, br * 0.62f, br * 0.55f, birdBelly)
            // Wing flutters a few degrees on the clock.
            val flutter = 6f * live * sin(TAU * clock)
            withTransform({ rotate(-16f + flutter, pivot = Offset(bx - br * 0.2f, by - br * 0.2f)) }) {
                drawArc(color = wing, startAngle = 150f, sweepAngle = 150f, useCenter = true, topLeft = Offset(bx - br * 0.5f, by - br * 0.4f), size = Size(br * 0.95f, br * 1.1f))
            }
            tri(bx - br * 0.1f, by - br * 0.9f, bx + br * 0.1f, by - br * 1.3f, bx + br * 0.28f, by - br * 0.85f, body)
            tri(bx + br * 0.85f, by - br * 0.15f, bx + br * 1.34f, by, bx + br * 0.85f, by + br * 0.18f, Color(0xFFF2B457))
            blinkingEye(bx + br * 0.36f, by - br * 0.18f, s * 0.022f, pulse(clock, 0.4f, 0.06f))
            drawCircle(blush.copy(alpha = 0.5f), br * 0.2f, Offset(bx + br * 0.62f, by + br * 0.12f))
        }

        // Loose leaves blowing across, drifting on the clock.
        for (k in 0 until 3) {
            val ph = (clock + k * 0.37f) % 1f
            val lx = x(0.06f) + ph * s * 0.92f
            val ly = y(0.30f + 0.12f * k) + sin(ph * TAU + k) * s * 0.05f
            val a = sin(PIf * ph)
            leaf(lx, ly, s * (0.055f - k * 0.006f), s * 0.03f, 20f + k * 40f + ph * 120f, leafDark.copy(alpha = a), leafLight.copy(alpha = 0f))
        }
    }

    /**
     * CRICKETS — the firefly drifting among swaying grass and a night blossom
     * under a crescent moon, its glow pulsing, a couple of extra glimmers. Floats.
     */
    fun sceneCricketsFirefly() {
        softGlow(x(0.5f), y(0.66f), s * 0.5f, accent, 0.2f)
        crescent(x(0.8f), y(0.16f), s * 0.055f, moonlight)
        sparkle(x(0.22f), y(0.14f), s * 0.02f, starColor.copy(alpha = 0.85f))
        sparkle(x(0.34f), y(0.24f), s * 0.014f, starColor.copy(alpha = 0.65f))
        sparkle(x(0.62f), y(0.2f), s * 0.016f, starColor.copy(alpha = 0.75f))

        // Tall grass blades swaying with the breathe.
        val bend0 = swing * s * 0.02f * live
        fun blade(baseX: Float, height: Float, bend: Float, color: Color) {
            val p = paths.main
            p.reset()
            val tipX = baseX + bend + bend0
            p.moveTo(baseX - s * 0.02f, y(1.0f))
            p.quadraticTo(baseX + bend * 0.5f, y(1.0f) - height * 0.6f, tipX, y(1.0f) - height)
            p.quadraticTo(baseX + bend * 0.5f, y(1.0f) - height * 0.6f, baseX + s * 0.02f, y(1.0f))
            p.close()
            drawPath(p, color)
        }
        blade(x(0.12f), s * 0.4f, s * 0.06f, grassDark)
        blade(x(0.2f), s * 0.52f, -s * 0.05f, grassLight)
        blade(x(0.86f), s * 0.46f, -s * 0.07f, grassDark)
        blade(x(0.78f), s * 0.56f, s * 0.05f, grassLight)
        blade(x(0.5f), s * 0.3f, s * 0.04f, grassDark)

        // Night blossom on a stem.
        val blx = x(0.28f); val bly = y(0.6f)
        drawLine(grassDark, Offset(blx, y(1.0f)), Offset(blx, bly), s * 0.01f, StrokeCap.Round)
        for (k in 0 until 5) {
            val ang = -PIf / 2f + k * (TAU / 5f)
            oval(blx + cos(ang) * s * 0.05f, bly + sin(ang) * s * 0.05f, s * 0.035f, s * 0.028f, petal)
        }
        drawCircle(petalCore, s * 0.03f, Offset(blx, bly))
        softGlow(blx, bly, s * 0.08f, petalCore, 0.35f + 0.1f * breathe)

        // Extra firefly glimmers, pulsing out of phase.
        val g1 = 0.4f + 0.4f * pulse(clock, 0.25f, 0.4f)
        softGlow(x(0.66f), y(0.42f), s * 0.07f, fireflyGlow, 0.5f * g1)
        drawCircle(fireflyGlow.copy(alpha = 0.7f * g1), s * 0.012f, Offset(x(0.66f), y(0.42f)))
        val g2 = 0.4f + 0.4f * pulse(clock, 0.7f, 0.4f)
        softGlow(x(0.16f), y(0.5f), s * 0.055f, fireflyGlow, 0.45f * g2)
        drawCircle(fireflyGlow.copy(alpha = 0.6f * g2), s * 0.01f, Offset(x(0.16f), y(0.5f)))

        // The firefly, drifting a little, its belly glow breathing.
        val dx = sin(TAU * clock) * s * 0.03f
        val dy = -abs(cos(TAU * clock)) * s * 0.015f - swing * s * 0.01f * live
        withTransform({ translate(dx, dy) }) {
            val cx = x(0.52f); val cy = y(0.5f); val fr = s * 0.16f
            val glow = 0.4f + 0.5f * breathe
            softGlow(cx, cy + fr * 1.4f, fr * 2.2f, fireflyGlow, 0.4f + 0.3f * breathe)
            oval(cx - fr * 0.7f, cy - fr * 0.3f, fr * 0.7f, fr * 0.44f, fireflyWing.copy(alpha = 0.5f))
            oval(cx + fr * 0.7f, cy - fr * 0.3f, fr * 0.7f, fr * 0.44f, fireflyWing.copy(alpha = 0.5f))
            drawLine(fireflyBody, Offset(cx - fr * 0.25f, cy - fr * 0.85f), Offset(cx - fr * 0.5f, cy - fr * 1.4f), s * 0.008f, StrokeCap.Round)
            drawLine(fireflyBody, Offset(cx + fr * 0.25f, cy - fr * 0.85f), Offset(cx + fr * 0.5f, cy - fr * 1.4f), s * 0.008f, StrokeCap.Round)
            drawCircle(fireflyGlow, s * 0.014f, Offset(cx - fr * 0.5f, cy - fr * 1.4f))
            drawCircle(fireflyGlow, s * 0.014f, Offset(cx + fr * 0.5f, cy - fr * 1.4f))
            drawCircle(fireflyBody, fr * 0.55f, Offset(cx, cy - fr * 0.6f))
            oval(cx, cy + fr * 0.25f, fr * 0.55f, fr * 0.7f, fireflyBody)
            beadEye(cx - fr * 0.2f, cy - fr * 0.65f, s * 0.017f)
            beadEye(cx + fr * 0.2f, cy - fr * 0.65f, s * 0.017f)
            drawCircle(fireflyGlow.copy(alpha = 0.45f + 0.4f * breathe), fr * 0.42f, Offset(cx, cy + fr * 0.7f))
        }
    }

    /**
     * STREAM — the duck floating free (no water block): the whimsical bottoms-up
     * dabble by default, ripple rings expanding, a reed/cattail or two, an
     * occasional tail wiggle; or the calmer head-dip toward a fish (variant 0).
     */
    fun sceneStreamDuck() {
        softGlow(x(0.5f), y(0.55f), s * 0.5f, accent, 0.16f)

        // A reed and a cattail, swaying with the breathe.
        val reedSway = swing * 3.5f * live
        fun reed(baseX: Float, height: Float, isCattail: Boolean) {
            val topX = baseX + s * 0.02f
            val topY = y(0.78f) - height
            withTransform({ rotate(reedSway, pivot = Offset(baseX, y(0.78f))) }) {
                drawLine(grassDark, Offset(baseX, y(0.78f)), Offset(topX, topY), s * 0.012f, StrokeCap.Round)
                if (isCattail) {
                    oval(topX, topY, s * 0.02f, s * 0.05f, cattail)
                } else {
                    leaf(topX + s * 0.01f, topY + height * 0.1f, s * 0.06f, s * 0.02f, -50f, grassLight, grassDark)
                }
            }
        }
        reed(x(0.84f), s * 0.42f, true)
        reed(x(0.9f), s * 0.34f, false)
        reed(x(0.8f), s * 0.3f, false)

        if (variant == 1) {
            // Bottoms-up dabble — tail up, head under, ripple rings expanding.
            val dcx = x(0.46f)
            val bob = swing * s * 0.012f * live
            val wiggle = pulse(clock, 0.45f, 0.12f) * 10f * live // occasional tail wiggle
            withTransform({ translate(0f, bob) }) {
                oval(dcx, y(0.6f), s * 0.16f, s * 0.13f, duckBody)
                withTransform({ rotate(-wiggle, pivot = Offset(dcx - s * 0.02f, y(0.5f))) }) {
                    tri(dcx - s * 0.04f, y(0.48f), dcx - s * 0.12f, y(0.36f), dcx + s * 0.02f, y(0.42f), duckDark)
                }
                drawLine(duckBeak, Offset(dcx + s * 0.05f, y(0.52f)), Offset(dcx + s * 0.12f, y(0.44f)), s * 0.014f, StrokeCap.Round)
                drawLine(duckBeak, Offset(dcx + s * 0.09f, y(0.54f)), Offset(dcx + s * 0.16f, y(0.48f)), s * 0.014f, StrokeCap.Round)
                oval(dcx + s * 0.08f, y(0.56f), s * 0.09f, s * 0.05f, duckDark)
            }
            // Ripple rings around the dunked head, expanding and fading.
            for (k in 0 until 3) {
                val ph = (clock + k / 3f) % 1f
                val rr = s * (0.05f + ph * 0.14f)
                val a = (1f - ph) * 0.55f
                drawArc(
                    color = foam.copy(alpha = a),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(dcx - rr, y(0.66f) - rr * 0.28f),
                    size = Size(rr * 2f, rr * 0.56f),
                    style = Stroke(width = s * 0.006f),
                )
            }
        } else {
            // Head-dip toward a fish, ripple rings where the beak meets the water.
            val dcx = x(0.42f); val dbase = y(0.62f); val dr = s * 0.16f
            val bob = swing * s * 0.01f * live
            withTransform({ translate(0f, bob) }) {
                tri(dcx - dr * 1.1f, dbase - dr * 0.4f, dcx - dr * 1.7f, dbase - dr * 0.8f, dcx - dr * 1.1f, dbase - dr * 0.05f, duckDark)
                oval(dcx, dbase - dr * 0.3f, dr * 1.15f, dr * 0.78f, duckBody)
                drawArc(color = duckDark, startAngle = 20f, sweepAngle = 150f, useCenter = false, topLeft = Offset(dcx - dr * 0.5f, dbase - dr * 0.85f), size = Size(dr * 1.2f, dr * 1.0f), style = Stroke(width = s * 0.01f, cap = StrokeCap.Round))
                val hx = dcx + dr * 1.0f; val hy = dbase - dr * 0.1f; val hr = dr * 0.62f
                drawLine(duckBody, Offset(dcx + dr * 0.5f, dbase - dr * 0.7f), Offset(hx, hy), dr * 0.5f, StrokeCap.Round)
                drawCircle(duckBody, hr, Offset(hx, hy))
                tri(hx + hr * 0.3f, hy + hr * 0.2f, hx + hr * 1.2f, hy + hr * 0.7f, hx + hr * 0.3f, hy + hr * 0.8f, duckBeak)
                blinkingEye(hx + hr * 0.1f, hy - hr * 0.1f, s * 0.016f, pulse(clock, 0.6f, 0.06f))
                drawCircle(blush.copy(alpha = 0.45f), hr * 0.28f, Offset(hx - hr * 0.4f, hy + hr * 0.2f))
            }
            val hx = dcx + dr * 1.0f; val hr = dr * 0.62f
            for (k in 0 until 3) {
                val ph = (clock + k / 3f) % 1f
                val rr = s * (0.04f + ph * 0.1f)
                val a = (1f - ph) * 0.55f
                drawArc(
                    color = foam.copy(alpha = a),
                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                    topLeft = Offset(hx + hr * 0.7f - rr, y(0.72f) - rr * 0.28f),
                    size = Size(rr * 2f, rr * 0.56f),
                    style = Stroke(width = s * 0.006f),
                )
            }
            val fx = hx + hr * 1.2f; val fy = y(0.78f)
            oval(fx, fy, s * 0.05f, s * 0.032f, fishBody)
            oval(fx - s * 0.01f, fy + s * 0.008f, s * 0.03f, s * 0.018f, fishBelly)
            tri(fx + s * 0.045f, fy, fx + s * 0.09f, fy - s * 0.03f, fx + s * 0.09f, fy + s * 0.03f, fishBody)
            drawCircle(eyeColor, s * 0.007f, Offset(fx - s * 0.02f, fy - s * 0.005f))
        }
    }

    when (kind) {
        CritterKind.CAT -> sceneCatNook()
        CritterKind.FROG -> sceneRainFrog()
        CritterKind.WHALE -> sceneOceanWhale()
        CritterKind.FOX -> sceneCampfireFox()
        CritterKind.BIRD -> sceneWindBird()
        CritterKind.FIREFLY -> sceneCricketsFirefly()
        CritterKind.DUCK -> sceneStreamDuck()
    }
}
