# Playback spec (`core/playback`)

## Goal
Own the running state of the app — mix, play/pause, master volume, sleep
timer, settings — in one process-wide controller that the UI, the foreground
service and the media session all observe. The service exists only to keep
the process foreground and to expose media controls; nothing decides anything
inside it.

## Public API (`dev.jtiisto.noise.core.playback`)
```kotlin
data class PlaybackSettings(
    val fadeOutSeconds: Int = 45,          // sleep-timer fade window (15/30/45/60/120)
    val mixWithOtherApps: Boolean = false, // true = never request audio focus
    val lastTimerMinutes: Int = 30,
)
data class TimerState(val totalMillis: Long, val endAtEpochMillis: Long, val remainingMillis: Long, val fadeMillis: Long) {
    val isFading get() = remainingMillis <= fadeMillis
}
data class PlaybackState(
    val mix: Mix = Mix.EMPTY,
    val isPlaying: Boolean = false,
    val masterVolume: Float = 0.8f,
    val timer: TimerState? = null,
    val settings: PlaybackSettings = PlaybackSettings(),
    val isDucked: Boolean = false,
)
enum class ToggleResult { ADDED, REMOVED, REJECTED_LIMIT }

interface PlaybackController {
    val state: StateFlow<PlaybackState>
    fun play()                      // no-op if mix is empty
    fun pause()
    fun togglePlay()
    fun toggleSound(id: SoundId): ToggleResult
    fun setLayerGain(id: SoundId, gain: Float)
    fun setMix(mix: Mix)            // scenes; keeps playing if playing
    fun clearMix()                  // also pauses
    fun setMasterVolume(volume: Float)
    fun startTimer(minutes: Int)    // restarts if one is running; remembers lastTimerMinutes
    fun cancelTimer()
    fun updateSettings(transform: (PlaybackSettings) -> PlaybackSettings)
}
```

## Components
- `DefaultPlaybackController(engine: AudioEngine, store: StateStore, focus: AudioFocusGate, serviceLauncher: ServiceLauncher, clock, scope)` —
  the only place state changes. Pure logic; all Android edges are interfaces
  injected by Koin and faked in tests:
  - `AudioEngine` (core/audio).
  - `StateStore` — persists `PersistedState(mix, masterVolume, settings,
    wasPlaying, timerEndAtEpochMillis, timerTotalMillis)`; `DataStoreStateStore`
    (Preferences DataStore, file `hush.preferences_pb`) is the real one.
  - `AudioFocusGate` — `request(): Boolean`, `abandon()`, and a callback flow of
    `FocusEvent { LOSS, LOSS_TRANSIENT, DUCK, GAIN }`. `AndroidAudioFocusGate`
    uses `AudioFocusRequest` (API 26+) with `USAGE_MEDIA`/`CONTENT_TYPE_MUSIC`,
    `willPauseWhenDucked=false`. Also emits `BECOMING_NOISY` from the
    `ACTION_AUDIO_BECOMING_NOISY` receiver (registered while playing only).
  - `ServiceLauncher` — `ensureStarted()` (startForegroundService) / nothing
    else; the service stops itself when it observes `isPlaying=false` for
    longer than 60 s or a STOP command.
- Rules:
  - `play()`: if `settings.mixWithOtherApps` is false, request focus; if
    denied, stay paused. Then `serviceLauncher.ensureStarted()`, `engine.setMix`,
    `engine.setMasterVolume`, `engine.start()`, persist `wasPlaying=true`.
  - `pause()`: `engine.stop()`, abandon focus (unless a transient loss caused
    the pause, in which case focus is kept so GAIN can resume), persist
    `wasPlaying=false`.
  - Focus: LOSS → pause; LOSS_TRANSIENT → pause and set `resumeOnGain`;
    DUCK → `engine.setDucked(true)`, `isDucked=true`; GAIN → unduck, and play
    again if `resumeOnGain`. BECOMING_NOISY → pause (no resume).
  - Timer: `startTimer(m)` sets `endAt = now + m min`, ticks every second
    (test dispatcher friendly), calls `engine.beginFadeOut(fadeMillis)` when
    `remaining <= fadeMillis` (once), and on completion pauses, clears the
    timer, persists. `cancelTimer()` → `engine.cancelFadeOut()` if fading.
    Changing `fadeOutSeconds` while a timer runs applies to that timer.
    Pausing manually cancels the timer.
  - Every state change is persisted (debounced 300 ms) via `StateStore`.
  - Process restart: on controller creation, load persisted state; if
    `wasPlaying` and the mix is non-empty, call `play()`; if a timer end time
    lies in the future, re-arm it with the remaining time; if it is in the
    past, treat as expired (stay paused, clear).
- `HushPlaybackService : MediaSessionService` — builds a `MediaSession`
  around `HushPlayer : SimpleBasePlayer`, which mirrors controller state:
  one `MediaItem` whose metadata is the mix title (`Mix.title()`) and subtitle
  "Sleep timer · 27 min" when a timer runs (updated once a minute), artwork =
  app icon. Available commands: PLAY_PAUSE, STOP, GET/SET volume off, seeking
  off. `handleSetPlayWhenReady(true)` → `controller.play()`, false →
  `controller.pause()`, `handleStop()` → `controller.pause()` then stop self.
  `onTaskRemoved` keeps playing if playing, otherwise stops. `START_STICKY`.
  Notification is Media3's default provider (`DefaultMediaNotificationProvider`)
  on channel `hush_playback`. The service module is device glue: excluded
  from coverage, kept minimal, every decision lives in the controller.
- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `POST_NOTIFICATIONS`, `WAKE_LOCK`; service `foregroundServiceType="mediaPlayback"`,
  exported with the `androidx.media3.session.MediaSessionService` intent
  filter.

## Tests
- Controller state machine with a fake engine, fake store, fake focus gate,
  `StandardTestDispatcher` + virtual time: play/pause/toggle, max-3 limit,
  gain updates, scene apply while playing, focus sequences (transient loss →
  gain resumes; permanent loss does not), becoming-noisy, timer tick/fade/
  completion, cancel mid-fade, settings change mid-timer, restore-on-restart
  (playing + timer in future / past), persistence debounce.
- `DataStoreStateStore` round-trip is a Robolectric-free unit test of the
  pure `PersistedState` ⇄ `Preferences` mapping functions (kept separate from
  the DataStore wrapper so no Android runtime is needed).
