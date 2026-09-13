package dev.jtiisto.noise.ui.critter

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jtiisto.noise.ui.theme.MixPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sin

private const val TAU = (2.0 * PI).toFloat()
private const val PIf = PI.toFloat()

/**
 * A small, gently-animated animal that keeps the play orb company.
 *
 * It is purely decorative (no semantics, so TalkBack skips it) and is drawn
 * as Compose vector art — no emoji, no bitmaps — from circles, ovals, arcs and
 * a single reused [Path], so a frame allocates nothing that grows.
 *
 * Motion follows the app's tight budget: **one** [rememberInfiniteTransition]
 * drives **two** looping values, both read inside the draw lambda so an
 * animating critter invalidates drawing without recomposing:
 *  - **breathe** — a slow 0..1 triangle (2.6 s while playing, 3.8 s while
 *    paused, ease-in-out, reversing) that becomes the whole-body bob-and-breathe
 *    and the firefly's belly glow.
 *  - **clock** — a 0..1 sawtooth (6 s playing, 9 s paused, linear, restarting)
 *    that every per-animal secondary motion is *derived* from with pure math:
 *    the cat's staggered "z" trail, the frog/bird/duck blinks, the fox ear
 *    twitch, the duck's nod, the whale's rising spout and the firefly's wing
 *    shimmer. No coroutines, timers or `delay` — a blink is just a window of the
 *    clock (see [pulse]).
 *
 * When the lead nature sound changes the animal swaps with a soft fade-and-scale
 * ([AnimatedContent]).
 *
 * @param palette read only inside the draw lambda, so a mix-colour transition
 *   never recomposes the critter (the same trick the orb uses).
 */
@Composable
fun Critter(
    kind: CritterKind,
    isPlaying: Boolean,
    palette: State<MixPalette>,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
) {
    val transition = rememberInfiniteTransition(label = "critter")
    val breathe = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 2_600 else 3_800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "critterBreathe",
    )
    // A monotonic loop (restart, not reverse) so secondary motions drift one way
    // and repeat, instead of bouncing: the "z"s rise and a new one follows, the
    // spout puffs upward, blinks happen once per turn of the loop.
    val clock = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isPlaying) 6_000 else 9_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "critterClock",
    )

    // The animal is redrawn each frame, so its Path is built once here and
    // reset() between shapes — one allocation for the whole critter, not one
    // per frame per shape.
    val path = remember { Path() }

    AnimatedContent(
        targetState = kind,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(360)) + scaleIn(tween(360), initialScale = 0.72f)) togetherWith
                (fadeOut(tween(240)) + scaleOut(tween(240), targetScale = 0.72f))
        },
        label = "critterSwap",
    ) { current ->
        Canvas(Modifier.size(size)) {
            drawCritter(current, breathe.value, clock.value, isPlaying, palette.value.accent, path)
        }
    }
}

/**
 * A critter pinned to explicit animation values — no live transition.
 *
 * `internal`, and used only by the screenshot references: a static render
 * captures one instant, so this lets a `@PreviewTest` freeze [breathe]/[clock]
 * at a chosen frame (a mid-blink frog, the "z" trail at two drift positions)
 * that the live [Critter] would only pass through.
 */
@Composable
internal fun CritterFrame(
    kind: CritterKind,
    breathe: Float,
    clock: Float,
    isPlaying: Boolean,
    palette: State<MixPalette>,
    modifier: Modifier = Modifier,
    size: Dp = 54.dp,
) {
    val path = remember { Path() }
    Canvas(modifier.size(size)) {
        drawCritter(kind, breathe, clock, isPlaying, palette.value.accent, path)
    }
}

// --- Motion maths (pure, unit-tested) ----------------------------------------

/**
 * A smooth, occasional pulse in `0..1`, derived from the looping [clock].
 *
 * It is `0` for almost the whole cycle, eases up to `1` at [center] and back
 * down over a window [width] wide (a fraction of the cycle), and repeats every
 * loop — a blink or a twitch, and never a strobe. The window wraps around the
 * `0..1` loop, so a [center] near an edge still eases symmetrically. Pure and
 * allocation-free; costs one `cos`.
 */
internal fun pulse(clock: Float, center: Float, width: Float): Float {
    val raw = clock - center
    val d = raw - round(raw)                 // signed distance to center in (-0.5, 0.5]
    val half = width / 2f
    if (abs(d) >= half) return 0f
    // Hann window: 1 at the center, 0 (with zero slope) at both edges.
    return 0.5f * (1f + cos(PIf * (d / half)))
}

// --- Palette -----------------------------------------------------------------
// A couple of soft fills per animal, chosen to read on the near-black night
// ground. Declared once (Color is an inline value class, so tinting with
// `.copy` / `lerp` inside a draw allocates nothing).

private val CatBody = Color(0xFFB9B3DA)
private val CatDark = Color(0xFF938CC0)
private val CatInnerEar = Color(0xFFE3A9C6)

private val FrogBody = Color(0xFF83CE84)
private val FrogDark = Color(0xFF5CAE64)
private val FrogBelly = Color(0xFFD3ECAF)

private val WhaleBody = Color(0xFF7DB0E8)
private val WhaleDark = Color(0xFF5B8ED2)
private val WhaleBelly = Color(0xFFDCEBFB)
private val Spout = Color(0xFFCFE6FA)

private val FoxBody = Color(0xFFEB9A57)
private val FoxDark = Color(0xFFD07C3D)
private val FoxCream = Color(0xFFF8E7D1)

private val DuckBody = Color(0xFFF3D268)
private val DuckDark = Color(0xFFE3BC4C)
private val DuckBeak = Color(0xFFEE9A46)
private val DuckBeakSplit = Color(0xFFCF7C36)

private val BirdBelly = Color(0xFFF4ECD4)
private val BirdBeak = Color(0xFFF2B457)

private val FireflyBody = Color(0xFF423E57)
private val FireflyGlow = Color(0xFFEBF69C)
private val FireflyWing = Color(0xFFDCE4F2)

private val Eye = Color(0xFF2A2740)
private val Shine = Color(0xFFFFFFFF)
private val Blush = Color(0xFFF2A6B4)

/**
 * Draws [kind] into the current square canvas.
 *
 * [breathe] is a 0..1 triangle wave — a gentle bob-and-breathe applied to the
 * whole animal (livelier while [playing]) plus the firefly glow. [clock] is a
 * 0..1 sawtooth that each animal turns into its own secondary motion (the cat's
 * "z" trail, blinks, the ear twitch, the spout, the wing shimmer).
 *
 * `internal` (not `private`) only so the off-device render test can drive it
 * against a stubbed canvas — there is no Compose UI test rig in this project.
 */
internal fun DrawScope.drawCritter(
    kind: CritterKind,
    breathe: Float,
    clock: Float,
    playing: Boolean,
    accent: Color,
    path: Path,
) {
    val s = size.minDimension
    val live = if (playing) 1f else 0.55f
    val swing = (breathe - 0.5f) * 2f                  // -1..1
    val bob = -swing * s * 0.045f * live               // drifts up and down
    val breatheScale = 1f + 0.035f * breathe * live    // a slow in-and-out

    withTransform({
        translate(0f, bob)
        // Breathe from the base, so the animal never looks like it is floating.
        scale(breatheScale, breatheScale, pivot = Offset(s * 0.5f, s * 0.96f))
    }) {
        when (kind) {
            CritterKind.CAT -> drawCat(s, accent, clock, path)
            CritterKind.FROG -> drawFrog(s, clock, live)
            CritterKind.WHALE -> drawWhale(s, clock, path)
            CritterKind.BIRD -> drawBird(s, accent, clock, live, path)
            CritterKind.FOX -> drawFox(s, clock, path)
            CritterKind.DUCK -> drawDuck(s, clock, live, path)
            CritterKind.FIREFLY -> drawFirefly(s, breathe, clock)
        }
    }
}

// --- Shared drawing helpers --------------------------------------------------

private fun DrawScope.oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) =
    drawOval(color, topLeft = Offset(cx - rx, cy - ry), size = Size(rx * 2, ry * 2))

private fun DrawScope.fillTriangle(path: Path, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, color: Color) {
    path.reset()
    path.moveTo(ax, ay)
    path.lineTo(bx, by)
    path.lineTo(cx, cy)
    path.close()
    drawPath(path, color)
}

/** A big, round, friendly eye: a dark bead with a single catch-light. */
private fun DrawScope.beadEye(cx: Float, cy: Float, r: Float) {
    drawCircle(Eye, r, Offset(cx, cy))
    drawCircle(Shine, r * 0.34f, Offset(cx - r * 0.28f, cy - r * 0.34f))
}

/**
 * A bead eye that blinks. When [closed] is ~0 it is a round, shiny bead; as it
 * closes the bead squashes flat and its catch-light fades, and once nearly shut
 * it becomes a soft upward curve — a happy closed eye, never a gap. [closed] is
 * a 0..1 pulse (see [pulse]), so a blink eases in and out and never strobes.
 */
private fun DrawScope.blinkingEye(cx: Float, cy: Float, r: Float, closed: Float) {
    val open = 1f - closed
    if (open > 0.15f) {
        oval(cx, cy, r, r * open, Eye)
        val shine = ((open - 0.4f) / 0.6f).coerceIn(0f, 1f)
        if (shine > 0f) {
            drawCircle(Shine.copy(alpha = shine), r * 0.34f, Offset(cx - r * 0.28f, cy - r * 0.30f))
        }
    } else {
        closedEye(cx, cy, r * 2.6f, Eye)
    }
}

/** A calm, closed eye — a shallow upward curve. */
private fun DrawScope.closedEye(cx: Float, cy: Float, w: Float, color: Color = Eye) {
    drawArc(
        color = color,
        startAngle = 22f,
        sweepAngle = 136f,
        useCenter = false,
        topLeft = Offset(cx - w / 2, cy - w / 2),
        size = Size(w, w),
        style = Stroke(width = w * 0.16f, cap = StrokeCap.Round),
    )
}

/** A gentle smile. */
private fun DrawScope.smile(cx: Float, cy: Float, w: Float, h: Float, color: Color, stroke: Float) {
    drawArc(
        color = color,
        startAngle = 20f,
        sweepAngle = 140f,
        useCenter = false,
        topLeft = Offset(cx - w / 2, cy - h / 2),
        size = Size(w, h),
        style = Stroke(width = stroke, cap = StrokeCap.Round),
    )
}

// --- The animals -------------------------------------------------------------

/** Curled up asleep, a staggered trail of "z"s drifting off the top corner. */
private fun DrawScope.drawCat(s: Float, accent: Color, clock: Float, path: Path) {
    fun x(f: Float) = f * s
    fun y(f: Float) = f * s

    // Curled body.
    oval(x(0.52f), y(0.70f), x(0.40f), y(0.26f), CatBody)
    // Tail sweeping across the front, tip resting up.
    drawArc(
        color = CatDark,
        startAngle = 40f,
        sweepAngle = 200f,
        useCenter = false,
        topLeft = Offset(x(0.16f), y(0.52f)),
        size = Size(x(0.62f), y(0.42f)),
        style = Stroke(width = s * 0.11f, cap = StrokeCap.Round),
    )
    // Head resting on the paws.
    val hx = x(0.35f); val hy = y(0.62f); val hr = x(0.205f)
    drawCircle(CatBody, hr, Offset(hx, hy))
    // Ears.
    fillTriangle(path, hx - hr * 0.86f, hy - hr * 0.62f, hx - hr * 0.30f, hy - hr * 1.28f, hx - hr * 0.02f, hy - hr * 0.74f, CatBody)
    fillTriangle(path, hx + hr * 0.10f, hy - hr * 0.78f, hx + hr * 0.44f, hy - hr * 1.30f, hx + hr * 0.78f, hy - hr * 0.66f, CatBody)
    fillTriangle(path, hx - hr * 0.62f, hy - hr * 0.66f, hx - hr * 0.30f, hy - hr * 1.02f, hx - hr * 0.14f, hy - hr * 0.70f, CatInnerEar)
    fillTriangle(path, hx + hr * 0.24f, hy - hr * 0.70f, hx + hr * 0.46f, hy - hr * 1.04f, hx + hr * 0.66f, hy - hr * 0.64f, CatInnerEar)
    // Sleeping eyes and a little nose.
    closedEye(hx - hr * 0.36f, hy + hr * 0.02f, hr * 0.62f)
    closedEye(hx + hr * 0.42f, hy + hr * 0.02f, hr * 0.62f)
    drawCircle(Blush.copy(alpha = 0.6f), hr * 0.24f, Offset(hx - hr * 0.02f, hy + hr * 0.30f))
    // Curled front paw.
    oval(x(0.30f), y(0.86f), x(0.16f), y(0.09f), CatDark)

    // A drowsy trail of three "z"s, tinted with the mix accent. Each rises up
    // and to the right and fades as it goes; they are staggered a third of the
    // loop apart so they read as a little sleep trail leaving the cat. Driven
    // by the looping clock — no timer.
    val stagger = 1f / 3f
    for (i in 0..2) {
        val f = clock - i * stagger
        val lp = f - floor(f)                          // 0..1 for this z
        val a = 0.9f * sin(PIf * lp)                    // fade in, then out
        if (a <= 0.02f) continue
        val zx = x(0.60f) + s * 0.15f * lp
        val zy = y(0.46f) - s * 0.34f * lp
        val zw = s * (0.085f + 0.06f * lp)             // grows as it floats away
        val st = zw * 0.22f
        val c = accent.copy(alpha = a)
        drawLine(c, Offset(zx, zy), Offset(zx + zw, zy), st, StrokeCap.Round)
        drawLine(c, Offset(zx + zw, zy), Offset(zx, zy + zw), st, StrokeCap.Round)
        drawLine(c, Offset(zx, zy + zw), Offset(zx + zw, zy + zw), st, StrokeCap.Round)
    }
}

/** A round, happy frog: eyes proud on top, an occasional blink and a soft throat pulse. */
private fun DrawScope.drawFrog(s: Float, clock: Float, live: Float) {
    fun x(f: Float) = f * s
    fun y(f: Float) = f * s

    // Feet.
    oval(x(0.20f), y(0.88f), x(0.13f), y(0.07f), FrogDark)
    oval(x(0.80f), y(0.88f), x(0.13f), y(0.07f), FrogDark)
    // Body.
    oval(x(0.50f), y(0.62f), x(0.38f), y(0.30f), FrogBody)
    // Belly, with a gentle throat pulse (a touch faster than the body breathe).
    val throat = 1f + 0.05f * live * sin(TAU * 2f * clock)
    oval(x(0.50f), y(0.72f), x(0.22f), y(0.19f) * throat, FrogBelly)
    // Eye mounds on top.
    val er = x(0.155f)
    drawCircle(FrogBody, er, Offset(x(0.34f), y(0.34f)))
    drawCircle(FrogBody, er, Offset(x(0.66f), y(0.34f)))
    drawCircle(Shine, er * 0.78f, Offset(x(0.34f), y(0.35f)))
    drawCircle(Shine, er * 0.78f, Offset(x(0.66f), y(0.35f)))
    // An occasional slow blink — eyes ease shut into happy arcs and open again.
    val closed = pulse(clock, 0.5f, 0.05f)
    blinkingEye(x(0.35f), y(0.37f), er * 0.44f, closed)
    blinkingEye(x(0.65f), y(0.37f), er * 0.44f, closed)
    // Wide smile.
    smile(x(0.50f), y(0.56f), x(0.40f), y(0.30f), FrogDark, s * 0.035f)
    drawCircle(Blush.copy(alpha = 0.5f), x(0.05f) * 1.2f, Offset(x(0.26f), y(0.60f)))
    drawCircle(Blush.copy(alpha = 0.5f), x(0.05f) * 1.2f, Offset(x(0.74f), y(0.60f)))
}

/** A little whale, its spout puffing upward and fading on a slow loop. */
private fun DrawScope.drawWhale(s: Float, clock: Float, path: Path) {
    fun x(f: Float) = f * s
    fun y(f: Float) = f * s

    // Tail flukes.
    fillTriangle(path, x(0.74f), y(0.62f), x(0.98f), y(0.46f), x(0.96f), y(0.66f), WhaleDark)
    fillTriangle(path, x(0.74f), y(0.66f), x(0.98f), y(0.72f), x(0.94f), y(0.82f), WhaleDark)
    // Body, head to the left.
    oval(x(0.44f), y(0.64f), x(0.36f), y(0.24f), WhaleBody)
    // Belly.
    oval(x(0.40f), y(0.74f), x(0.26f), y(0.13f), WhaleBelly)
    // Mouth line.
    smile(x(0.26f), y(0.70f), x(0.26f), y(0.16f), WhaleDark, s * 0.028f)
    // Eye.
    beadEye(x(0.24f), y(0.60f), s * 0.05f)
    // Blowhole spout — a steady little fountain with a droplet cluster that
    // rises and fades on a slow loop, so it always reads as spouting.
    val bx = x(0.32f); val by = y(0.42f)
    drawLine(Spout.copy(alpha = 0.75f), Offset(bx, by), Offset(bx, by - s * 0.11f), s * 0.03f, StrokeCap.Round)
    drawCircle(Spout, s * 0.045f, Offset(bx, by - s * 0.16f))          // steady cap
    val pa = sin(PIf * clock)                          // 0 -> 1 -> 0, no pop at the wrap
    val py = by - s * (0.20f + 0.14f * clock)          // droplets drift upward
    drawCircle(Spout.copy(alpha = pa * 0.9f), s * 0.032f, Offset(bx, py))
    drawCircle(Spout.copy(alpha = pa * 0.7f), s * 0.028f, Offset(bx - s * 0.10f, py + s * 0.05f))
    drawCircle(Spout.copy(alpha = pa * 0.7f), s * 0.028f, Offset(bx + s * 0.10f, py + s * 0.05f))
}

/** A round puffball bird, gently tinted with the wind accent; blinks and flutters a wing. */
private fun DrawScope.drawBird(s: Float, accent: Color, clock: Float, live: Float, path: Path) {
    fun x(f: Float) = f * s
    fun y(f: Float) = f * s

    val body = lerp(accent, Color.White, 0.34f)
    val wing = lerp(accent, Color.Black, 0.14f)
    // Feet.
    drawLine(BirdBeak, Offset(x(0.42f), y(0.92f)), Offset(x(0.42f), y(0.98f)), s * 0.03f, StrokeCap.Round)
    drawLine(BirdBeak, Offset(x(0.58f), y(0.92f)), Offset(x(0.58f), y(0.98f)), s * 0.03f, StrokeCap.Round)
    // Tail.
    fillTriangle(path, x(0.06f), y(0.52f), x(0.30f), y(0.48f), x(0.28f), y(0.70f), wing)
    // Body puff.
    drawCircle(body, x(0.34f), Offset(x(0.54f), y(0.58f)))
    // Belly.
    oval(x(0.54f), y(0.68f), x(0.20f), y(0.18f), BirdBelly)
    // Wing, fluttering a few degrees around its shoulder.
    val flutter = 5f * live * sin(TAU * clock)
    withTransform({ rotate(flutter, pivot = Offset(x(0.42f), y(0.50f))) }) {
        drawArc(
            color = wing,
            startAngle = 150f,
            sweepAngle = 150f,
            useCenter = true,
            topLeft = Offset(x(0.36f), y(0.44f)),
            size = Size(x(0.30f), y(0.34f)),
        )
    }
    // A tiny tuft.
    fillTriangle(path, x(0.52f), y(0.26f), x(0.58f), y(0.16f), x(0.62f), y(0.28f), body)
    // Beak, pointing out.
    fillTriangle(path, x(0.80f), y(0.52f), x(0.94f), y(0.56f), x(0.80f), y(0.60f), BirdBeak)
    // Eye, with an occasional blink.
    blinkingEye(x(0.66f), y(0.50f), s * 0.055f, pulse(clock, 0.4f, 0.05f))
    drawCircle(Blush.copy(alpha = 0.5f), s * 0.05f, Offset(x(0.74f), y(0.60f)))
}

/** A fox curled cosy asleep, its bushy tail wrapped round the front and an occasional ear twitch. */
private fun DrawScope.drawFox(s: Float, clock: Float, path: Path) {
    fun x(f: Float) = f * s
    fun y(f: Float) = f * s

    // Body.
    oval(x(0.54f), y(0.70f), x(0.38f), y(0.24f), FoxBody)
    // Bushy tail wrapping across the front.
    drawArc(
        color = FoxBody,
        startAngle = 30f,
        sweepAngle = 210f,
        useCenter = false,
        topLeft = Offset(x(0.10f), y(0.50f)),
        size = Size(x(0.64f), y(0.44f)),
        style = Stroke(width = s * 0.15f, cap = StrokeCap.Round),
    )
    // Cream tail tip.
    drawCircle(FoxCream, s * 0.085f, Offset(x(0.20f), y(0.86f)))
    // Head.
    val hx = x(0.36f); val hy = y(0.60f); val hr = x(0.205f)
    drawCircle(FoxBody, hr, Offset(hx, hy))
    // Left ear (still).
    fillTriangle(path, hx - hr * 0.92f, hy - hr * 0.50f, hx - hr * 0.58f, hy - hr * 1.42f, hx - hr * 0.06f, hy - hr * 0.72f, FoxBody)
    fillTriangle(path, hx - hr * 0.70f, hy - hr * 0.56f, hx - hr * 0.52f, hy - hr * 1.06f, hx - hr * 0.22f, hy - hr * 0.68f, FoxDark)
    // Right ear, with an occasional quick flick around its base.
    val twitch = pulse(clock, 0.6f, 0.05f)
    withTransform({ rotate(twitch * 13f, pivot = Offset(hx + hr * 0.4f, hy - hr * 0.55f)) }) {
        fillTriangle(path, hx + hr * 0.08f, hy - hr * 0.74f, hx + hr * 0.60f, hy - hr * 1.42f, hx + hr * 0.92f, hy - hr * 0.50f, FoxBody)
        fillTriangle(path, hx + hr * 0.24f, hy - hr * 0.68f, hx + hr * 0.52f, hy - hr * 1.06f, hx + hr * 0.70f, hy - hr * 0.56f, FoxDark)
    }
    // Cream cheeks / muzzle.
    oval(hx, hy + hr * 0.34f, hr * 0.72f, hr * 0.56f, FoxCream)
    // Cosy closed eyes and nose.
    closedEye(hx - hr * 0.40f, hy - hr * 0.02f, hr * 0.58f)
    closedEye(hx + hr * 0.40f, hy - hr * 0.02f, hr * 0.58f)
    drawCircle(Eye, hr * 0.14f, Offset(hx, hy + hr * 0.42f))
}

/** A cheerful rubber-duck facing right, nodding gently with an occasional blink. */
private fun DrawScope.drawDuck(s: Float, clock: Float, live: Float, path: Path) {
    fun x(f: Float) = f * s
    fun y(f: Float) = f * s

    // Tail flick.
    fillTriangle(path, x(0.14f), y(0.60f), x(0.02f), y(0.52f), x(0.16f), y(0.72f), DuckDark)
    // Body.
    oval(x(0.44f), y(0.66f), x(0.34f), y(0.22f), DuckBody)
    // Wing.
    drawArc(
        color = DuckDark,
        startAngle = 20f,
        sweepAngle = 150f,
        useCenter = false,
        topLeft = Offset(x(0.28f), y(0.50f)),
        size = Size(x(0.34f), y(0.30f)),
        style = Stroke(width = s * 0.03f, cap = StrokeCap.Round),
    )
    // Head group — a gentle nod (dip and tilt) around the neck, plus a blink.
    val nod = sin(TAU * clock)
    withTransform({
        rotate(2.5f * live * nod, pivot = Offset(x(0.58f), y(0.48f)))
        translate(0f, s * 0.012f * live * nod)
    }) {
        val hx = x(0.68f); val hy = y(0.40f); val hr = x(0.19f)
        drawCircle(DuckBody, hr, Offset(hx, hy))
        // Beak with a soft split line.
        fillTriangle(path, x(0.84f), y(0.38f), x(0.99f), y(0.42f), x(0.84f), y(0.47f), DuckBeak)
        drawLine(DuckBeakSplit, Offset(x(0.85f), y(0.425f)), Offset(x(0.98f), y(0.42f)), s * 0.012f, StrokeCap.Round)
        // Eye, with an occasional blink.
        blinkingEye(x(0.70f), y(0.37f), s * 0.05f, pulse(clock, 0.5f, 0.05f))
        drawCircle(Blush.copy(alpha = 0.45f), s * 0.045f, Offset(x(0.62f), y(0.46f)))
    }
}

/** A firefly whose belly glows in a slow blink, its wings shimmering softly. */
private fun DrawScope.drawFirefly(s: Float, breathe: Float, clock: Float) {
    fun x(f: Float) = f * s
    fun y(f: Float) = f * s

    val cx = x(0.5f)
    // Soft wings behind the body, shimmering gently out of phase with each other.
    val shimmer = 0.18f * sin(TAU * clock)
    oval(x(0.34f), y(0.44f), x(0.16f), y(0.11f), FireflyWing.copy(alpha = 0.42f + shimmer))
    oval(x(0.66f), y(0.44f), x(0.16f), y(0.11f), FireflyWing.copy(alpha = 0.42f - shimmer))
    // Antennae with little tips.
    drawLine(FireflyBody, Offset(x(0.44f), y(0.28f)), Offset(x(0.36f), y(0.16f)), s * 0.022f, StrokeCap.Round)
    drawLine(FireflyBody, Offset(x(0.56f), y(0.28f)), Offset(x(0.64f), y(0.16f)), s * 0.022f, StrokeCap.Round)
    drawCircle(FireflyGlow, s * 0.03f, Offset(x(0.36f), y(0.16f)))
    drawCircle(FireflyGlow, s * 0.03f, Offset(x(0.64f), y(0.16f)))
    // Head and thorax.
    drawCircle(FireflyBody, x(0.115f), Offset(cx, y(0.34f)))
    oval(cx, y(0.52f), x(0.115f), x(0.14f), FireflyBody)
    // Face.
    beadEye(x(0.46f), y(0.33f), s * 0.03f)
    beadEye(x(0.54f), y(0.33f), s * 0.03f)
    // Glowing abdomen — the blink, on the slow breathe.
    val glowA = 0.35f + 0.55f * breathe
    val gy = y(0.74f); val gr = s * 0.30f
    drawCircle(
        brush = Brush.radialGradient(
            0f to FireflyGlow.copy(alpha = glowA),
            0.45f to FireflyGlow.copy(alpha = glowA * 0.55f),
            1f to FireflyGlow.copy(alpha = 0f),
            center = Offset(cx, gy),
            radius = gr,
        ),
        radius = gr,
        center = Offset(cx, gy),
    )
    drawCircle(FireflyGlow.copy(alpha = 0.5f + 0.4f * breathe), s * 0.10f, Offset(cx, gy))
}
