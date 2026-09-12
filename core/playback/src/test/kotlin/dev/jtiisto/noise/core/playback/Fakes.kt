package dev.jtiisto.noise.core.playback

import dev.jtiisto.noise.core.audio.AudioEngine
import dev.jtiisto.noise.core.model.Mix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Records what the controller asked of the engine and lets a test drive the
 * sleep-timer fade callback by hand (the real engine fires it from its audio
 * thread when the fade envelope reaches zero).
 */
class FakeAudioEngine : AudioEngine {
    override var isRunning: Boolean = false
        private set

    var startCount = 0
        private set
    var stopCount = 0
        private set
    var mix: Mix = Mix.EMPTY
        private set
    var masterVolume: Float = -1f
        private set
    var ducked: Boolean = false
        private set
    val duckCalls = mutableListOf<Boolean>()
    val fadeDurations = mutableListOf<Long>()
    var cancelFadeCount = 0
        private set

    private var onFadeComplete: (() -> Unit)? = null

    /**
     * The callback handed to the last [beginFadeOut], kept even after a stop or
     * cancel so a test can fire a *stale* completion the way a real audio
     * thread would when it raced a timer restart.
     */
    var lastFadeCallback: (() -> Unit)? = null
        private set

    val fadeStartCount: Int get() = fadeDurations.size
    val isFading: Boolean get() = onFadeComplete != null

    override fun start() {
        startCount++
        isRunning = true
    }

    override fun stop() {
        stopCount++
        isRunning = false
        onFadeComplete = null
    }

    override fun setMix(mix: Mix) {
        this.mix = mix
    }

    override fun setMasterVolume(volume: Float) {
        masterVolume = volume
    }

    override fun setDucked(ducked: Boolean) {
        this.ducked = ducked
        duckCalls += ducked
    }

    override fun beginFadeOut(durationMs: Long, onComplete: () -> Unit) {
        fadeDurations += durationMs
        onFadeComplete = onComplete
        lastFadeCallback = onComplete
    }

    override fun cancelFadeOut() {
        cancelFadeCount++
        onFadeComplete = null
    }

    /** Simulates the fade envelope reaching zero. */
    fun completeFade() {
        val callback = requireNotNull(onFadeComplete) { "no fade in progress" }
        onFadeComplete = null
        isRunning = false
        callback()
    }
}

class FakeStateStore(
    private val initial: PersistedState = PersistedState(),
    /**
     * When set, [load] suspends on it — that is the cold-start window in which
     * the user can already be tapping tiles. Complete it to let restore finish.
     */
    private val loadGate: CompletableDeferred<Unit>? = null,
    /** When set, [load] throws it — a disk that cannot be read at all. */
    private val loadFailure: Throwable? = null,
) : StateStore {
    val saves = mutableListOf<PersistedState>()
    var loadCount = 0
        private set

    val last: PersistedState? get() = saves.lastOrNull()

    override suspend fun load(): PersistedState {
        loadGate?.await()
        loadCount++
        loadFailure?.let { throw it }
        return initial
    }

    override suspend fun save(state: PersistedState) {
        saves += state
    }
}

class FakeAudioFocusGate : AudioFocusGate {
    var granted = true
    var requestCount = 0
        private set
    var abandonCount = 0
        private set

    private val _events = MutableSharedFlow<FocusEvent>(extraBufferCapacity = 16)
    override val events: Flow<FocusEvent> = _events.asSharedFlow()

    override fun request(): Boolean {
        requestCount++
        return granted
    }

    override fun abandon() {
        abandonCount++
    }

    fun emit(event: FocusEvent) {
        check(_events.tryEmit(event)) { "focus event buffer overflow" }
    }
}

class FakeServiceLauncher : ServiceLauncher {
    var startCount = 0
        private set

    override fun ensureStarted() {
        startCount++
    }
}

/**
 * Records wake-lock traffic and, crucially, whether it is currently held, so a
 * test can assert the lock mirrors playback exactly and is never leaked.
 */
class FakeWakeLock : WakeLock {
    var held = false
        private set
    var acquireCount = 0
        private set
    var releaseCount = 0
        private set

    override fun acquire() {
        acquireCount++
        held = true
    }

    override fun release() {
        releaseCount++
        held = false
    }
}

/**
 * Wall clock pinned to the test scheduler's virtual time, so `advanceTimeBy`
 * moves the sleep timer exactly as much as it moves `delay`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
    private val epochAtStart: Long = EPOCH_START,
) : Clock {
    /**
     * How many times the clock was read. The timer's tick reads it exactly
     * once per second, so this is how a test proves only one tick loop is
     * running — two loops produce identical `remainingMillis` and are
     * otherwise invisible.
     */
    var reads = 0
        private set

    override fun now(): Long {
        reads++
        return epochAtStart + scheduler.currentTime
    }

    companion object {
        /** An arbitrary but realistic "now" (2023-11-14T22:13:20Z). */
        const val EPOCH_START = 1_700_000_000_000L
    }
}
