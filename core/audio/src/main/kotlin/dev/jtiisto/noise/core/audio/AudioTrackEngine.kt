package dev.jtiisto.noise.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import dev.jtiisto.noise.core.model.Mix
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android sink: one dedicated thread pumping [MixRenderer] into an
 * `AudioTrack`.
 *
 * This class is deliberately thin — it owns a thread, a track and a buffer,
 * and nothing else. Every decision about *what* to render (crossfades, fades,
 * ducking, clipping, when a fade has finished) lives in [MixRenderer], which
 * runs on the JVM and is unit-tested; this file is excluded from coverage
 * precisely because there is nothing here that could be tested without a
 * device.
 *
 * Notable choices:
 *  * **`ENCODING_PCM_FLOAT`.** The renderer works in float; converting to
 *    16-bit here would add a quantisation step for no benefit, and float is
 *    supported on every device at API 26+.
 *  * **4x the minimum buffer.** Sleep sounds run for eight hours on a phone
 *    that will happily deschedule us; a deep buffer costs a little latency,
 *    which is irrelevant here, and buys a lot of underrun immunity.
 *  * **`THREAD_PRIORITY_URGENT_AUDIO`.** Without it the render thread is a
 *    normal background thread and drops out when the UI does anything.
 *  * **The device's native sample rate.** If the output runs at 44.1 kHz and
 *    we hand it 48 kHz, the framework resamples — extra CPU all night, and a
 *    slightly duller top end. Asking `AudioTrack` what the hardware wants and
 *    building the renderer at that rate avoids it entirely.
 */
class AudioTrackEngine(
    private val config: EngineConfig = EngineConfig(),
) : AudioEngine {

    private val lock = Any()
    private val running = AtomicBoolean(false)
    private val shouldStop = AtomicBoolean(false)

    private var thread: Thread? = null

    /**
     * Built at the device's real output rate, which may differ from
     * [EngineConfig.sampleRate]. Created eagerly so parameters can be set
     * before [start] and survive stop/start cycles.
     */
    private val effectiveConfig: EngineConfig = config.copy(sampleRate = resolveSampleRate(config))

    private val renderer = MixRenderer(effectiveConfig)

    /** Size of the AudioTrack buffer; also how much silence a clean teardown drains. */
    private val bufferBytes: Int = computeBufferBytes()

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        synchronized(lock) {
            // Clearing the stop flag first means a start() that arrives during
            // a fade-out simply turns it back into a fade-in on the same
            // thread, with no gap and no second AudioTrack.
            shouldStop.set(false)
            renderer.start()
            if (running.get() && thread != null) return
            running.set(true)
            val t = Thread(::renderLoop, THREAD_NAME)
            thread = t
            t.start()
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!running.get()) return
            // Ask the renderer to fade; the loop exits once it reports
            // finished, so the fade is actually heard rather than cut off.
            renderer.stop()
            shouldStop.set(true)
        }
    }

    override fun setMix(mix: Mix) = renderer.setMix(mix)

    override fun setMasterVolume(volume: Float) = renderer.setMasterVolume(volume)

    override fun setDucked(ducked: Boolean) = renderer.setDucked(ducked)

    override fun beginFadeOut(durationMs: Long, onComplete: () -> Unit) =
        renderer.beginFadeOut(durationMs) {
            onComplete()
            stop()
        }

    override fun cancelFadeOut() = renderer.cancelFadeOut()

    // ---- Render thread --------------------------------------------------------

    private fun renderLoop() {
        // Best effort: some OEM builds refuse the audio nice level for
        // unprivileged apps and throw. Losing the boost is a soft degradation;
        // an exception escaping here would kill the process, so never let it.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            .onFailure { Log.w(TAG, "could not raise audio thread priority", it) }

        val frames = effectiveConfig.blockFrames
        val left = FloatArray(frames)
        val right = FloatArray(frames)
        val interleaved = FloatArray(frames * CHANNELS)

        var track: AudioTrack? = null
        var recreated = false
        var lastUnderruns = 0

        try {
            var current = createTrack().also { it.play() }
            track = current
            while (true) {
                // Render until a stop has been requested AND its fade has
                // actually reached silence, so the fade is heard, not cut.
                while (!shouldStop.get() || !renderer.isFinished) {
                    renderer.render(left, right, frames)
                    var j = 0
                    for (i in 0 until frames) {
                        interleaved[j++] = left[i]
                        interleaved[j++] = right[i]
                    }
                    // `write` may take fewer floats than it was offered, even in
                    // WRITE_BLOCKING mode. Anything it left behind would be
                    // overwritten by the next render — a gap punched into the
                    // middle of a fade (engine review #9, ported from Notch) — so
                    // keep offering the rest of the block until it is all gone.
                    var offset = 0
                    var stalled = 0
                    while (offset < interleaved.size) {
                        val written = current.write(
                            interleaved,
                            offset,
                            interleaved.size - offset,
                            AudioTrack.WRITE_BLOCKING,
                        )
                        if (written == 0) {
                            // A blocking write returns everything or an error, so
                            // this should not happen; if a stopped track ever
                            // produces it, give up on the block rather than
                            // spinning a core all night.
                            if (++stalled >= MAX_STALLED_WRITES) {
                                Log.w(TAG, "AudioTrack accepted nothing; dropping a block")
                                break
                            }
                            continue
                        }
                        stalled = 0
                        if (written < 0) {
                            if (written == AudioTrack.ERROR_DEAD_OBJECT && !recreated) {
                                // The audio server restarted (or the device changed).
                                // One rebuild is worth attempting; a second failure
                                // means something is genuinely wrong and looping would
                                // just burn battery.
                                Log.w(TAG, "AudioTrack died, recreating once")
                                recreated = true
                                releaseTrack(current)
                                current = createTrack().also { it.play() }
                                track = current
                                // The new track's buffer is empty, so the block
                                // goes in again from the start rather than being
                                // abandoned half-written.
                                offset = 0
                                continue
                            }
                            Log.e(TAG, "AudioTrack write failed: $written")
                            return
                        }
                        offset += written
                    }
                    val underruns = current.underrunCount
                    if (underruns != lastUnderruns) {
                        Log.w(TAG, "AudioTrack underruns: $underruns")
                        lastUnderruns = underruns
                    }
                }
                // The renderer has faded to silence, but a blocking write only
                // promises the samples were *queued*: the tail of the fade is
                // still ahead of the playback head, and the pause()/flush() in
                // releaseTrack would throw it away (engine review #8, ported from
                // Notch). Push one buffer of silence through first — the write
                // cannot return until the audio in front of it has been played,
                // which is the drain.
                drainQueuedAudio(current, interleaved)
                // The exit decision is taken under the same lock start() uses:
                // a start() that lands while the fade was finishing has cleared
                // shouldStop and re-armed the renderer, so we keep this thread
                // and this track instead of tearing down and leaving the engine
                // silent with running == true.
                //
                // Invariant (engine review #2, ported from Notch 2026-09-15):
                // "this thread is about to die" and "running == false" must
                // become visible together, under this lock. Clearing them in
                // the finally block instead left a window where start() saw
                // running == true, returned happily, and then watched the only
                // render thread exit — playback requested, nothing playing.
                synchronized(lock) {
                    if (shouldStop.get()) {
                        thread = null
                        running.set(false)
                        return
                    }
                }
            }
        } catch (e: RuntimeException) {
            // An exception escaping the audio thread would take the whole
            // process down in the middle of the night; log and go quiet instead.
            Log.e(TAG, "audio thread stopped", e)
        } finally {
            // The normal exit above already cleared these under the lock; this
            // is the error path (a write failure or an escaped exception), where
            // the same invariant still has to hold. Clear the running state
            // FIRST, under the lock: releaseTrack() spends real time in JNI, and
            // a start() landing in that window must see "not running" and spawn a
            // new thread rather than no-op against one already committed to exiting.
            synchronized(lock) {
                if (thread === Thread.currentThread()) {
                    thread = null
                    running.set(false)
                }
            }
            track?.let { releaseTrack(it) }
        }
    }

    /**
     * Writes one buffer's worth of silence, which returns only once everything
     * queued ahead of it has actually been played (engine review #8). Aborts the
     * moment a [start] clears `shouldStop`: the caller then goes back to
     * rendering instead of spending the rest of the buffer on silence.
     */
    private fun drainQueuedAudio(track: AudioTrack, scratch: FloatArray) {
        var remaining = bufferBytes / BYTES_PER_FLOAT
        scratch.fill(0f)
        while (remaining > 0 && shouldStop.get()) {
            val written = track.write(
                scratch,
                0,
                minOf(remaining, scratch.size),
                AudioTrack.WRITE_BLOCKING,
            )
            if (written <= 0) return // dead or refused: nothing left to drain through
            remaining -= written
        }
    }

    private fun computeBufferBytes(): Int {
        val minBytes = AudioTrack.getMinBufferSize(
            effectiveConfig.sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val blockBytes = effectiveConfig.blockFrames * CHANNELS * BYTES_PER_FLOAT
        return maxOf(minBytes * BUFFER_MULTIPLIER, blockBytes * 2)
    }

    private fun createTrack(): AudioTrack {
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(effectiveConfig.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun releaseTrack(track: AudioTrack) {
        runCatching {
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.pause()
                track.flush()
                track.stop()
            }
        }
        runCatching { track.release() }
    }

    private companion object {
        const val TAG = "HushAudio"
        const val THREAD_NAME = "hush-audio"
        const val CHANNELS = 2
        const val BYTES_PER_FLOAT = 4
        const val BUFFER_MULTIPLIER = 4

        /** Consecutive zero-progress writes tolerated before a block is abandoned. */
        const val MAX_STALLED_WRITES = 3

        /**
         * Prefers the hardware's own output rate so the framework never has to
         * resample us. Falls back to the configured rate if the query fails
         * (it returns 0 on some emulators).
         */
        fun resolveSampleRate(config: EngineConfig): Int {
            val native = runCatching {
                AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
            }.getOrDefault(0)
            return if (native in 8_000..192_000) native else config.sampleRate
        }
    }
}
