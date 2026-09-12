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
- `DefaultPlaybackController(engine: AudioEngine, store: StateStore, focus: AudioFocusGate, serviceLauncher: ServiceLauncher, clock: Clock, scope: CoroutineScope, mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate)` —
  the only place state changes. Pure logic; all Android edges are interfaces
  injected by Koin and faked in tests. Every command is scheduled on
  `mainDispatcher`, so the state machine is single-threaded and lock-free;
  with `Main.immediate` a call already on the main thread runs inline.
  `toggleSound` is the exception that must answer synchronously, so it decides
  from the current snapshot and only schedules the mutation.
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
  - `play()`: if `settings.mixWithOtherApps` is false and we do not already
    hold focus, request it; if denied, stay paused and change nothing else.
    Then `serviceLauncher.ensureStarted()`, `engine.setMix`,
    `engine.setMasterVolume`, `engine.start()`, persist `wasPlaying=true`.
    Idempotent, and a no-op while the mix is empty.
  - `pause()`: `engine.stop()`, abandon focus (unless a transient loss caused
    the pause, in which case focus is kept so GAIN can resume), unduck, persist
    `wasPlaying=false`.
  - Emptying the mix pauses: `clearMix()`, `setMix(Mix.EMPTY)`, and
    `toggleSound` removing the last layer all take the `pause()` path.
  - Focus: LOSS → pause; LOSS_TRANSIENT → pause and set `resumeOnGain` (only
    if we were playing); DUCK → `engine.setDucked(true)`, `isDucked=true`;
    GAIN → unduck, and play again if `resumeOnGain`. BECOMING_NOISY → pause
    (no resume). Toggling `mixWithOtherApps` while playing takes effect at
    once: turning it on abandons focus, turning it off requests it.
  - Timer: `startTimer(m)` clamps `m` to 5..480, sets `endAt = now + m min`,
    ticks every second (test dispatcher friendly), and calls
    `engine.beginFadeOut(remainingMillis)` exactly once when
    `remaining <= fadeMillis`. The fade runs over what is actually *left*, not
    the nominal window, so a window longer than the remaining time cannot
    outlive the timer. Completion (the engine's `onComplete`) pauses, clears
    the timer and flushes. `cancelTimer()` → `engine.cancelFadeOut()` if
    fading. Changing `fadeOutSeconds` while a timer runs applies to that timer
    and can open the fade window immediately.
  - Only a *user* pause cancels the timer. A pause forced on us (focus loss,
    becoming-noisy) leaves the wall-clock timer running, since the user did
    not change their mind about when the sound should end; if it runs out
    while we are paused the tick completes it (no engine callback is coming).
    Resuming inside the fade window re-issues `beginFadeOut` for the time
    that is left.
  - Every state change is persisted (debounced 300 ms) via `StateStore`.
    Flushed immediately on `pause()`, `startTimer()`, `cancelTimer()`, timer
    completion and any focus-driven pause.
  - Process restart: on controller creation, load persisted state; if
    `wasPlaying` and the mix is non-empty, call `play()`; if a timer end time
    lies in the future, re-arm it with the remaining time; if it is in the
    past, treat as expired — stay paused (playback *would* have stopped while
    the process was dead), clear the timer and flush the cleared state.
- `HushPlaybackService : MediaSessionService` — builds a `MediaSession`
  around `HushPlayer : SimpleBasePlayer`, which mirrors controller state:
  one `MediaItem` whose metadata is the mix title (`Mix.title()`) and subtitle
  "Sleep timer · 27 min" when a timer runs (updated once a minute — the
  service refreshes the session only when the play state, the title or the
  whole minutes left change, not on every one-second tick). Available
  commands: PLAY_PAUSE, STOP, plus the read-only GET_CURRENT_MEDIA_ITEM /
  GET_TIMELINE / GET_METADATA a media controller needs to display the title;
  volume and seeking off. `handleSetPlayWhenReady(true)` → `controller.play()`,
  false → `controller.pause()`, `handleStop()` → `controller.pause()` then
  stop self. `onTaskRemoved` keeps playing if playing, otherwise stops.
  `START_STICKY`. The service stops itself after 60 s of `isPlaying=false`.
  Notification is Media3's default provider (`DefaultMediaNotificationProvider`)
  on channel `hush_playback`; the service creates that channel itself in
  `onCreate` so it carries a description (Media3 only creates a channel that
  does not exist yet). Channel name and description are string resources in
  `core/playback/src/main/res/values/strings.xml`. The service module is
  device glue: excluded from coverage, kept minimal, every decision lives in
  the controller.
- Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `POST_NOTIFICATIONS`, `WAKE_LOCK` in the **app** manifest; the service is
  declared in the **library** manifest (`core/playback/src/main/`) with
  `foregroundServiceType="mediaPlayback"`, exported, and the
  `androidx.media3.session.MediaSessionService` intent filter.
- DI: `playbackModule` (`dev.jtiisto.noise.core.playback.di`) binds
  `PlaybackController` (eager), `StateStore`, `AudioFocusGate`,
  `ServiceLauncher`, `Clock` and the application `CoroutineScope`
  (`SupervisorJob + Main.immediate`, qualifier `ApplicationScope`). It
  deliberately does **not** bind `AudioEngine` — the app supplies that from
  `core/audio`.

## Tests
- Controller state machine with a fake engine, fake store, fake focus gate,
  `StandardTestDispatcher` + virtual time: play/pause/toggle, max-3 limit,
  gain updates, scene apply while playing, focus sequences (transient loss →
  gain resumes; permanent loss does not), becoming-noisy, timer tick/fade/
  completion, cancel mid-fade, settings change mid-timer, restore-on-restart
  (playing + timer in future / past), persistence debounce.
- The controller's `mainDispatcher` in tests is an `UnconfinedTestDispatcher`
  sharing the scheduler — the test-time equivalent of `Main.immediate`, so
  commands apply inline while `delay` still runs on virtual time. Tests must
  cancel the controller's scope when they finish: a running sleep timer always
  has a tick scheduled, so `advanceUntilIdle` would never return.
- `DataStoreStateStore` round-trip is a Robolectric-free unit test of the
  pure `PersistedState` ⇄ `Preferences` mapping functions (kept separate from
  the DataStore wrapper so no Android runtime is needed).
