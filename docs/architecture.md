# Architecture

```
┌────────────┐   state (StateFlow)   ┌──────────────────────┐
│  Compose   │◄──────────────────────┤  PlaybackController  │  single source of truth
│  UI (app)  │──── commands ────────►│  (core/playback)     │  mix · play/pause · timer ·
└────────────┘                       │                      │  focus · settings · persistence
                                     └──┬─────────┬─────────┘
                    mirrors state       │         │ drives
┌────────────────────────┐              │         ▼
│ HushPlaybackService    │◄─────────────┘   ┌────────────────┐    ┌──────────────┐
│ MediaSessionService +  │                  │  AudioEngine   │───►│  AudioTrack  │
│ HushPlayer(SimpleBase) │                  │  (core/audio)  │    └──────────────┘
└────────────────────────┘                  │  MixRenderer   │
   notification · lock-screen               │  generators    │
   controls · foreground lifetime           └────────────────┘
```

- **Decisions live in one place.** `DefaultPlaybackController` owns every
  rule (focus, timer, limits, persistence). The service and the media player
  are mirrors; the UI is a pure function of `PlaybackState`.
- **Android edges are interfaces** (`AudioEngine`, `AudioFocusGate`,
  `StateStore`, `ServiceLauncher`) so the controller is tested with fakes and
  virtual time.
- **The render loop is allocation-free.** Parameters cross into the audio
  thread through one atomic snapshot read per block; every gain change is a
  ramp.
- **Process death is survivable.** `was_playing` + a wall-clock timer end are
  persisted; the sticky service restarts and the controller resumes.

See `specs/` for the contracts and `docs/sound-design.md` for how each sound
is built.
