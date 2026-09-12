package dev.jtiisto.noise.core.audio

import dev.jtiisto.noise.core.audio.dsp.EqualPowerFade
import dev.jtiisto.noise.core.audio.dsp.LinearRamp
import dev.jtiisto.noise.core.audio.dsp.SoftClipper
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything the audio thread needs to know, captured as one immutable object.
 *
 * The audio thread reads this reference exactly once per [MixRenderer.render]
 * call. That single read is the whole concurrency design: no locks on the hot
 * path, no queue to drain, no possibility of seeing half of one caller's
 * change and half of another's, and no way for a slow render to be starved by
 * a UI thread hammering a volume slider.
 */
internal data class MixSnapshot(
    val version: Long,
    val mix: Mix,
    val masterVolume: Float,
    val ducked: Boolean,
    val playing: Boolean,
    val fadeOut: FadeOutRequest?,
)

/** An armed sleep-timer fade. [id] distinguishes re-arms of the same duration. */
internal class FadeOutRequest(
    val id: Long,
    val durationSamples: Int,
    val onComplete: () -> Unit,
)

/**
 * The pure-Kotlin heart of the engine: turns a [Mix] into interleaved-free
 * stereo float blocks, with every gain change smoothed.
 *
 * Responsibilities, all of them decisions [AudioTrackEngine] deliberately does
 * not make:
 *
 *  * hold up to `2 * Mix.MAX_LAYERS` layer slots — [Mix.MAX_LAYERS] sounding
 *    plus the same number still fading out, so replacing a full mix crossfades
 *    properly instead of cutting;
 *  * map the UI slider to amplitude with `slider^2`, which tracks perceived
 *    loudness far better than a linear fader;
 *  * ramp *every* gain: per-layer crossfades, master volume, duck, start
 *    fade-in, stop fade-out and the sleep-timer fade. No gain step ever
 *    reaches the output un-ramped, which is what "no zipper noise" means in
 *    practice;
 *  * call the sleep-fade completion callback exactly once;
 *  * soft-clip the sum.
 *
 * ### Allocation
 * [render] allocates nothing. Generators for every catalog entry are built up
 * front (a few hundred kilobytes in total) rather than on demand, because
 * building one on the audio thread would allocate; the scratch buffers are
 * sized from [EngineConfig.blockFrames] at construction; the snapshot read is
 * a plain reference load; and the inner loops use arrays and indices only —
 * no iterators, no boxing, no captured lambdas.
 */
class MixRenderer @JvmOverloads constructor(
    private val config: EngineConfig,
    seed: Long = DEFAULT_SEED,
    factory: GeneratorFactory = Generators,
) {

    val sampleRate: Int get() = config.sampleRate

    /** True once a stop fade or a sleep fade has run to silence; the sink may release. */
    @Volatile
    var isFinished: Boolean = false
        private set

    // ---- Parameter plumbing (any thread) ------------------------------------

    private val lock = Any()
    private var version = 0L
    private var fadeIdCounter = 0L
    private var pending = MixSnapshot(
        version = 0L,
        mix = Mix.EMPTY,
        masterVolume = 1f,
        ducked = false,
        playing = false,
        fadeOut = null,
    )
    private val snapshotRef = AtomicReference(pending)

    // ---- Render-thread state -------------------------------------------------

    private val generators: Array<SoundGenerator> =
        Array(SoundId.entries.size) { ordinal ->
            val id = SoundId.entries[ordinal]
            factory.create(id, config.sampleRate, seed + ordinal * SEED_STRIDE)
        }

    private val slots = Array(Mix.MAX_LAYERS * 2) { LayerSlot() }
    private val scratchLeft = FloatArray(config.blockFrames)
    private val scratchRight = FloatArray(config.blockFrames)

    private val masterRamp = LinearRamp(1f)
    private val duckRamp = LinearRamp(1f)

    private var startPhase = 0f
    private var startTarget = 0f
    private var startStep = 0f

    private var sleepPhase = 1f
    private var sleepTarget = 1f
    private var sleepStep = 0f

    private var appliedVersion = -1L
    private var appliedMix: Mix? = null
    private var armedFadeId = 0L
    private var pendingCompletion: (() -> Unit)? = null
    private var startedOnce = false
    private var playing = false

    private val masterRampSamples = msToSamples(MASTER_RAMP_MS)
    private val duckRampSamples = msToSamples(DUCK_RAMP_MS)
    private val fadeInSamples = msToSamples(config.fadeInMs.toFloat())
    private val fadeOutSamples = msToSamples(config.fadeOutMs.toFloat())
    private val crossfadeSamples = msToSamples(config.layerCrossfadeMs.toFloat())

    // ---- Public API ----------------------------------------------------------

    /** Idempotent: fades in from wherever the envelope currently is. */
    fun start() = publish { it.copy(playing = true) }

    /** Idempotent: fades out; [isFinished] flips once the envelope reaches zero. */
    fun stop() = publish { it.copy(playing = false) }

    fun setMix(mix: Mix) = publish { it.copy(mix = mix) }

    fun setMasterVolume(volume: Float) = publish { it.copy(masterVolume = volume.coerceIn(0f, 1f)) }

    fun setDucked(ducked: Boolean) = publish { it.copy(ducked = ducked) }

    /**
     * Arms the sleep-timer fade. [onComplete] runs exactly once, on the render
     * thread, at the moment the envelope reaches zero — and never at all if
     * [cancelFadeOut] gets there first.
     */
    fun beginFadeOut(durationMs: Long, onComplete: () -> Unit) = synchronized(lock) {
        val samples = ((durationMs * config.sampleRate) / 1000L).toInt().coerceAtLeast(1)
        val request = FadeOutRequest(++fadeIdCounter, samples, onComplete)
        pending = pending.copy(version = ++version, fadeOut = request)
        snapshotRef.set(pending)
    }

    /** Restores gain smoothly. Safe to call when no fade is armed. */
    fun cancelFadeOut() = publish { it.copy(fadeOut = null) }

    /** Layers currently sounding or fading; for tests and diagnostics. */
    val activeLayerCount: Int
        get() {
            var n = 0
            for (i in slots.indices) if (slots[i].inUse) n++
            return n
        }

    /**
     * Renders [frames] frames into [left] and [right] (overwritten, not
     * accumulated). [frames] must not exceed [EngineConfig.blockFrames].
     */
    fun render(left: FloatArray, right: FloatArray, frames: Int) {
        require(frames <= scratchLeft.size) {
            "frames $frames exceeds blockFrames ${scratchLeft.size}"
        }
        require(frames <= left.size && frames <= right.size) { "output buffers too small" }
        if (frames <= 0) return

        applySnapshot()

        // A steady envelope lets the whole block skip the per-sample envelope
        // arithmetic; a steady *and zero* envelope also lets it skip running
        // the generators, which is what makes a paused engine cost nothing.
        val steady = masterRamp.isAtTarget && duckRamp.isAtTarget &&
            startPhase == startTarget && sleepPhase == sleepTarget
        val steadyEnvelope = masterRamp.value * duckRamp.value *
            EqualPowerFade.value(startPhase) * EqualPowerFade.value(sleepPhase)

        left.fill(0f, 0, frames)
        right.fill(0f, 0, frames)

        if (steady && steadyEnvelope == 0f) {
            updateCompletionState()
            return
        }

        renderLayers(left, right, frames)

        if (steady) {
            val e = steadyEnvelope
            for (i in 0 until frames) {
                left[i] = SoftClipper.clip(left[i] * e)
                right[i] = SoftClipper.clip(right[i] * e)
            }
        } else {
            for (i in 0 until frames) {
                val m = masterRamp.next()
                val d = duckRamp.next()
                startPhase = advance(startPhase, startTarget, startStep)
                sleepPhase = advance(sleepPhase, sleepTarget, sleepStep)
                val e = m * d *
                    EqualPowerFade.value(startPhase) * EqualPowerFade.value(sleepPhase)
                left[i] = SoftClipper.clip(left[i] * e)
                right[i] = SoftClipper.clip(right[i] * e)
            }
        }

        updateCompletionState()
    }

    // ---- Internals -----------------------------------------------------------

    private inline fun publish(transform: (MixSnapshot) -> MixSnapshot) {
        synchronized(lock) {
            pending = transform(pending).copy(version = ++version)
            snapshotRef.set(pending)
        }
    }

    private fun applySnapshot() {
        val snapshot = snapshotRef.get()
        if (snapshot.version == appliedVersion) return
        appliedVersion = snapshot.version

        masterRamp.rampTo(snapshot.masterVolume, masterRampSamples)
        duckRamp.rampTo(if (snapshot.ducked) config.duckGain else 1f, duckRampSamples)

        playing = snapshot.playing
        if (snapshot.playing) {
            startedOnce = true
            startTarget = 1f
            startStep = 1f / fadeInSamples
        } else {
            startTarget = 0f
            startStep = 1f / fadeOutSamples
        }

        val fade = snapshot.fadeOut
        if (fade == null) {
            if (armedFadeId != 0L) {
                // Cancelled before completion: climb back to full over the
                // normal fade-in time and drop the callback on the floor.
                armedFadeId = 0L
                pendingCompletion = null
                sleepTarget = 1f
                sleepStep = 1f / fadeInSamples
            }
        } else if (fade.id != armedFadeId) {
            armedFadeId = fade.id
            pendingCompletion = fade.onComplete
            sleepTarget = 0f
            // Scale the step by the current phase so a re-armed fade still
            // takes exactly the requested time from wherever it is now.
            sleepStep = (sleepPhase / fade.durationSamples).coerceAtLeast(MIN_STEP)
        }

        // Reference comparison, not equality: `copy()` preserves the mix
        // instance when only the volume or duck changed, so a slider drag
        // cannot restart (and thereby stall) an in-flight layer crossfade.
        if (snapshot.mix !== appliedMix) {
            appliedMix = snapshot.mix
            applyMix(snapshot.mix)
        }
    }

    private fun applyMix(mix: Mix) {
        for (i in slots.indices) slots[i].seen = false

        val layers = mix.layers
        for (i in 0 until layers.size) {
            val layer = layers[i]
            val ordinal = layer.id.ordinal
            var slot = findSlot(ordinal)
            if (slot == null) slot = claimSlot(ordinal)
            slot.seen = true
            // slider^2: a linear fader spends most of its travel in a range
            // the ear barely distinguishes; squaring makes the bottom half of
            // the slider useful.
            slot.gain.rampTo(layerGain(layer.gain), crossfadeSamples)
        }

        for (i in slots.indices) {
            val slot = slots[i]
            if (slot.inUse && !slot.seen) slot.gain.rampTo(0f, crossfadeSamples)
        }
    }

    private fun findSlot(ordinal: Int): LayerSlot? {
        for (i in slots.indices) {
            val slot = slots[i]
            // Reviving a slot that is still fading out is important: allocating
            // a second slot for the same sound would render that generator
            // twice and double its level.
            if (slot.inUse && slot.ordinal == ordinal) return slot
        }
        return null
    }

    private fun claimSlot(ordinal: Int): LayerSlot {
        for (i in slots.indices) {
            val slot = slots[i]
            if (!slot.inUse) {
                slot.ordinal = ordinal
                slot.inUse = true
                slot.gain.jumpTo(0f)
                return slot
            }
        }
        // Every slot busy (only reachable by replacing a full mix twice inside
        // one crossfade): steal the quietest, which is the furthest through
        // its fade-out and therefore the least audible.
        var quietest = slots[0]
        for (i in slots.indices) {
            if (slots[i].gain.value < quietest.gain.value) quietest = slots[i]
        }
        quietest.ordinal = ordinal
        quietest.gain.jumpTo(0f)
        return quietest
    }

    private fun renderLayers(left: FloatArray, right: FloatArray, frames: Int) {
        val sl = scratchLeft
        val sr = scratchRight
        for (s in slots.indices) {
            val slot = slots[s]
            if (!slot.inUse) continue
            val ramp = slot.gain
            if (ramp.value == 0f && ramp.isAtTarget) {
                slot.inUse = false
                continue
            }
            generators[slot.ordinal].render(sl, sr, frames)
            if (ramp.isAtTarget) {
                val g = ramp.value
                for (i in 0 until frames) {
                    left[i] += sl[i] * g
                    right[i] += sr[i] * g
                }
            } else {
                for (i in 0 until frames) {
                    val g = ramp.next()
                    left[i] += sl[i] * g
                    right[i] += sr[i] * g
                }
            }
            if (ramp.value == 0f && ramp.isAtTarget) slot.inUse = false
        }
    }

    private fun updateCompletionState() {
        if (armedFadeId != 0L && sleepPhase == 0f) {
            val callback = pendingCompletion
            pendingCompletion = null
            // armedFadeId stays set so a late cancelFadeOut cannot re-open the
            // fade after the callback has already fired; a fresh
            // beginFadeOut gets a new id and re-arms normally.
            isFinished = true
            callback?.invoke()
            return
        }
        if (startedOnce && !playing && startPhase == 0f) isFinished = true
        if (playing && startPhase > 0f) isFinished = false
    }

    private fun msToSamples(ms: Float): Int =
        (ms * config.sampleRate / 1000f).toInt().coerceAtLeast(1)

    private class LayerSlot {
        var ordinal = -1
        var inUse = false
        var seen = false
        val gain = LinearRamp(0f)
    }

    companion object {
        const val DEFAULT_SEED = 0x5EED_1234_5678_9ABCL

        /** Master-volume and duck slew times. 20 ms is inaudible yet click-free. */
        private const val MASTER_RAMP_MS = 20f
        private const val DUCK_RAMP_MS = 120f
        private const val SEED_STRIDE = -0x61c8_8646_80b5_83ebL // golden-ratio odd constant
        private const val MIN_STEP = 1e-7f

        /** The UI slider -> amplitude mapping. Public so the UI can show a matching curve. */
        fun layerGain(slider: Float): Float {
            val s = slider.coerceIn(0f, 1f)
            return s * s
        }

        private fun advance(value: Float, target: Float, step: Float): Float {
            if (value == target) return value
            return if (value < target) {
                val next = value + step
                if (next > target) target else next
            } else {
                val next = value - step
                if (next < target) target else next
            }
        }
    }
}
