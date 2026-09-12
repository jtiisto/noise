package dev.jtiisto.noise.core.playback

import dev.jtiisto.noise.core.audio.AudioEngine
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one object that decides anything. See specs/playback.md.
 *
 * Every public method only *schedules* work on [mainDispatcher]; the state
 * machine itself therefore runs single-threaded and needs no locks. With the
 * production `Dispatchers.Main.immediate` a call already on the main thread
 * runs inline, so UI callbacks stay synchronous. Nothing here blocks: the
 * engine, focus gate and service launcher are all fire-and-forget, and
 * persistence happens in a coroutine.
 *
 * [toggleSound] is the one method that must answer immediately (the UI shows a
 * "3 layers max" hint from the result), so it decides from the current state
 * snapshot and schedules only the mutation.
 */
class DefaultPlaybackController(
    private val engine: AudioEngine,
    private val store: StateStore,
    private val focus: AudioFocusGate,
    private val serviceLauncher: ServiceLauncher,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : PlaybackController {

    private val _state = MutableStateFlow(PlaybackState())
    override val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Set by a transient focus loss so [FocusEvent.GAIN] knows to resume. */
    private var resumeOnGain = false

    /** We hold the system focus request; kept across a transient loss so GAIN reaches us. */
    private var holdsFocus = false

    private var timerJob: Job? = null
    private var saveJob: Job? = null

    /** Guards "fade starts exactly once" across the many ticks inside the fade window. */
    private var fadeStarted = false

    /** Restoring writes the state we just read; persisting it back would be pointless churn. */
    private var restoring = false

    init {
        scope.launch(mainDispatcher) {
            focus.events.collect { onFocusEvent(it) }
        }
        scope.launch(mainDispatcher) { restore() }
    }

    // ---------------------------------------------------------------- commands

    override fun play() = dispatch { startPlayback() }

    override fun pause() = dispatch { pauseByUser() }

    override fun togglePlay() = dispatch {
        if (_state.value.isPlaying) pauseByUser() else startPlayback()
    }

    override fun toggleSound(id: SoundId): ToggleResult {
        val mix = _state.value.mix
        return when {
            mix.contains(id) -> {
                val next = mix.without(id)
                dispatch { applyMix(next) }
                ToggleResult.REMOVED
            }

            mix.isFull -> ToggleResult.REJECTED_LIMIT

            else -> {
                val next = requireNotNull(mix.with(id)) { "mix reported room but refused $id" }
                dispatch { applyMix(next) }
                ToggleResult.ADDED
            }
        }
    }

    override fun setLayerGain(id: SoundId, gain: Float) = dispatch {
        val mix = _state.value.mix
        if (!mix.contains(id)) return@dispatch
        applyMix(mix.gain(id, gain))
    }

    override fun setMix(mix: Mix) = dispatch { applyMix(mix) }

    override fun clearMix() = dispatch {
        _state.update { it.copy(mix = Mix.EMPTY) }
        pauseByUser()
    }

    override fun setMasterVolume(volume: Float) = dispatch {
        val clamped = volume.coerceIn(0f, 1f)
        if (clamped == _state.value.masterVolume) return@dispatch
        _state.update { it.copy(masterVolume = clamped) }
        // Told to the engine even when paused so the next start() is already correct.
        engine.setMasterVolume(clamped)
        schedulePersist()
    }

    override fun startTimer(minutes: Int) = dispatch {
        val clamped = minutes.coerceIn(
            PlaybackSettings.TIMER_MIN_MINUTES,
            PlaybackSettings.TIMER_MAX_MINUTES,
        )
        _state.update { it.copy(settings = it.settings.copy(lastTimerMinutes = clamped)) }
        stopTimer(cancelFade = true)
        if (!_state.value.isPlaying && !_state.value.mix.isEmpty) startPlayback()
        val total = clamped * MILLIS_PER_MINUTE
        armTimer(totalMillis = total, endAtEpochMillis = clock.now() + total)
        persistNow()
    }

    override fun cancelTimer() = dispatch {
        if (_state.value.timer == null) return@dispatch
        stopTimer(cancelFade = true)
        persistNow()
    }

    override fun updateSettings(transform: (PlaybackSettings) -> PlaybackSettings) = dispatch {
        val next = transform(_state.value.settings)
        if (next == _state.value.settings) return@dispatch
        _state.update { it.copy(settings = next) }

        // "Mix with other apps" toggled while playing must take effect now, not
        // at the next play(): the whole point is to sit under an audiobook.
        if (next.mixWithOtherApps && holdsFocus) {
            focus.abandon()
            holdsFocus = false
        } else if (!next.mixWithOtherApps && !holdsFocus && _state.value.isPlaying) {
            holdsFocus = focus.request()
        }

        // A new fade window applies to the timer that is already running.
        val timer = _state.value.timer
        if (timer != null) {
            _state.update { it.copy(timer = timer.copy(fadeMillis = next.fadeOutSeconds * MILLIS_PER_SECOND)) }
            maybeBeginFade()
        }
        schedulePersist()
    }

    // ------------------------------------------------------------ state machine

    /** Returns true when playback is running afterwards. */
    private fun startPlayback(): Boolean {
        val current = _state.value
        if (current.isPlaying) return true
        if (current.mix.isEmpty) return false

        if (!current.settings.mixWithOtherApps && !holdsFocus) {
            if (!focus.request()) return false // denied: stay paused, change nothing
            holdsFocus = true
        }

        serviceLauncher.ensureStarted()
        engine.setMix(current.mix)
        engine.setMasterVolume(current.masterVolume)
        engine.start()
        _state.update { it.copy(isPlaying = true) }

        // Resuming inside the fade window (e.g. after a phone call) has to
        // re-issue the fade or the mix would jump back to full volume and then
        // cut out abruptly when the timer completes.
        val timer = _state.value.timer
        if (fadeStarted && timer != null) {
            engine.beginFadeOut(timer.remainingMillis) { onEngineFadeComplete() }
        }

        schedulePersist()
        return true
    }

    /** A user-initiated pause: cancels the sleep timer and never resumes by itself. */
    private fun pauseByUser() {
        resumeOnGain = false
        stopTimer(cancelFade = true)
        pauseInternal(abandonFocus = true)
        persistNow()
    }

    private fun pauseInternal(abandonFocus: Boolean) {
        if (_state.value.isDucked) {
            engine.setDucked(false)
            _state.update { it.copy(isDucked = false) }
        }
        if (_state.value.isPlaying) {
            engine.stop()
            _state.update { it.copy(isPlaying = false) }
        }
        if (abandonFocus && holdsFocus) {
            focus.abandon()
            holdsFocus = false
        }
    }

    private fun applyMix(mix: Mix) {
        _state.update { it.copy(mix = mix) }
        if (mix.isEmpty) {
            // Nothing left to play; pausing also drops the foreground service.
            pauseByUser()
            return
        }
        if (_state.value.isPlaying) engine.setMix(mix)
        schedulePersist()
    }

    // ------------------------------------------------------------------- timer

    private fun armTimer(totalMillis: Long, endAtEpochMillis: Long) {
        fadeStarted = false
        _state.update {
            it.copy(
                timer = TimerState(
                    totalMillis = totalMillis,
                    endAtEpochMillis = endAtEpochMillis,
                    remainingMillis = (endAtEpochMillis - clock.now()).coerceAtLeast(0L),
                    fadeMillis = it.settings.fadeOutSeconds * MILLIS_PER_SECOND,
                ),
            )
        }
        maybeBeginFade() // a timer shorter than the fade window fades from the start
        timerJob = scope.launch(mainDispatcher) {
            while (isActive) {
                delay(TICK_MILLIS)
                tick()
            }
        }
    }

    private fun tick() {
        val timer = _state.value.timer ?: return
        val remaining = (timer.endAtEpochMillis - clock.now()).coerceAtLeast(0L)
        _state.update { it.copy(timer = timer.copy(remainingMillis = remaining)) }
        maybeBeginFade()
        // Normally the engine's fade callback completes the timer. When we are
        // paused (a focus loss during the fade) no callback is coming, so the
        // tick has to finish the job itself.
        if (remaining == 0L && !_state.value.isPlaying) completeTimer()
    }

    private fun maybeBeginFade() {
        val current = _state.value
        val timer = current.timer ?: return
        if (fadeStarted || !timer.isFading) return
        fadeStarted = true
        if (!current.isPlaying) return // nothing to fade; startPlayback() re-issues it
        // Fade over what is actually left, not the nominal window: a fade
        // window longer than the remaining time would outlive the timer.
        engine.beginFadeOut(timer.remainingMillis) { onEngineFadeComplete() }
    }

    /** Called from the engine's thread. */
    private fun onEngineFadeComplete() = dispatch { completeTimer() }

    private fun completeTimer() {
        if (_state.value.timer == null) return // already cancelled or completed
        stopTimer(cancelFade = false)
        resumeOnGain = false
        pauseInternal(abandonFocus = true)
        persistNow()
    }

    private fun stopTimer(cancelFade: Boolean) {
        timerJob?.cancel()
        timerJob = null
        if (cancelFade && fadeStarted && _state.value.isPlaying) engine.cancelFadeOut()
        fadeStarted = false
        if (_state.value.timer != null) _state.update { it.copy(timer = null) }
    }

    // ------------------------------------------------------------------- focus

    private fun onFocusEvent(event: FocusEvent) {
        when (event) {
            FocusEvent.LOSS -> {
                resumeOnGain = false
                pauseInternal(abandonFocus = true)
                persistNow()
            }

            FocusEvent.LOSS_TRANSIENT -> {
                // Only arm the resume if we were actually playing, otherwise a
                // call taken while paused would start the mix when it ends.
                resumeOnGain = _state.value.isPlaying
                pauseInternal(abandonFocus = false) // keep focus so GAIN reaches us
                persistNow()
            }

            FocusEvent.DUCK -> {
                engine.setDucked(true)
                _state.update { it.copy(isDucked = true) }
            }

            FocusEvent.GAIN -> {
                if (_state.value.isDucked) {
                    engine.setDucked(false)
                    _state.update { it.copy(isDucked = false) }
                }
                if (resumeOnGain) {
                    resumeOnGain = false
                    startPlayback()
                }
            }

            FocusEvent.BECOMING_NOISY -> {
                // Headphones out: the user is done, never resume.
                resumeOnGain = false
                pauseInternal(abandonFocus = true)
                persistNow()
            }
        }
    }

    // ------------------------------------------------------------- persistence

    private suspend fun restore() {
        val persisted = store.load()
        val now = clock.now()
        val hasTimer = persisted.timerEndAtEpochMillis > 0L
        val timerExpired = hasTimer && persisted.timerEndAtEpochMillis <= now

        restoring = true
        try {
            _state.update {
                it.copy(
                    mix = persisted.mix,
                    masterVolume = persisted.masterVolume,
                    settings = persisted.settings,
                )
            }
            // An expired timer means playback *should* already have stopped
            // while we were dead: come back paused, not blaring at 4am.
            if (persisted.wasPlaying && !persisted.mix.isEmpty && !timerExpired) startPlayback()
            if (hasTimer && !timerExpired) {
                armTimer(
                    totalMillis = persisted.timerTotalMillis,
                    endAtEpochMillis = persisted.timerEndAtEpochMillis,
                )
            }
        } finally {
            restoring = false
        }
        if (timerExpired) persistNow() // clear the stale timer on disk
    }

    private fun schedulePersist() {
        if (restoring) return
        saveJob?.cancel()
        saveJob = scope.launch(mainDispatcher) {
            delay(PERSIST_DEBOUNCE_MILLIS)
            store.save(snapshot())
        }
    }

    /** Flush point: anything the user would be upset to lose if the process died now. */
    private fun persistNow() {
        if (restoring) return
        saveJob?.cancel()
        val snapshot = snapshot()
        saveJob = scope.launch(mainDispatcher) { store.save(snapshot) }
    }

    private fun snapshot(): PersistedState {
        val current = _state.value
        return PersistedState(
            mix = current.mix,
            masterVolume = current.masterVolume,
            settings = current.settings,
            wasPlaying = current.isPlaying,
            timerEndAtEpochMillis = current.timer?.endAtEpochMillis ?: 0L,
            timerTotalMillis = current.timer?.totalMillis ?: 0L,
        )
    }

    private fun dispatch(block: () -> Unit) {
        scope.launch(mainDispatcher) { block() }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val PERSIST_DEBOUNCE_MILLIS = 300L
        const val MILLIS_PER_SECOND = 1_000L
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
