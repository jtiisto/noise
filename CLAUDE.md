# Hush (noise)

## Overview
Native Android sleep-sound app: layered coloured noise + procedurally
synthesized ambience (rain, ocean, wind, fire…), sleep timer, background
playback with media controls. Personal side-loaded app — no network, no
accounts. App name **Hush**, package `dev.jtiisto.noise`.

Targets modest hardware (minSdk 26, targetSdk 36, compileSdk 37): the audio
engine is allocation-free pure Kotlin on one thread, UI motion is minimal, the
release build is R8-shrunk and bundles no audio files.

## Tech Stack
- Kotlin 2.3 (AGP 9 built-in) + Jetpack Compose (BOM 2026.08), Material 3
- Media3 session (`MediaSessionService` + `SimpleBasePlayer` over our engine)
- Koin (DI), Preferences DataStore, kotlinx.coroutines
- JUnit 5 + MockK + Turbine; Kover coverage gate; Compose Preview screenshot
  tests (JVM, no emulator)

## Module Structure
```
noise/
├── build-logic/        # convention plugins: noise.android.application|library
├── app/                # MainActivity, Koin bootstrap, Compose UI (ui/), service registration
├── core/model/         # SoundId catalog, Mix, Scenes — pure data
├── core/audio/         # DSP generators, MixRenderer, AudioTrackEngine
├── core/playback/      # PlaybackController, timer, focus, persistence, MediaSessionService
├── specs/              # living specs (product, audio-engine, playback, ui, persistence)
├── docs/               # architecture notes, sound-design notes, review log
├── githooks/           # pre-commit (unit tests) / pre-push (full suite + coverage + screenshots)
└── bin/                # git-commit-push.sh (detached commit/push helper)
```

## Workflow
- Spec-driven: `specs/` is the source of truth; update the spec first when the
  code needs to diverge. Follow `~/dev/CLAUDE.md` and `~/dev/native/CLAUDE.md`.
- Commits/pushes go through `bin/git-commit-push.sh` launched detached (see
  `~/dev/CLAUDE.md`). Hooks: `git config core.hooksPath githooks`.
- Gate commands (run from the repo root):
  - `./gradlew testDebugUnitTest` — unit suite (pre-commit when code is staged)
  - `./gradlew testDebugUnitTest koverVerifyAggregated` — coverage gate (pre-push)
  - `./gradlew validateDebugScreenshotTest` — screenshot references (pre-push)
  - `./gradlew updateDebugScreenshotTest` — regenerate references after an intended UI change
  - `NOISE_RENDER_DIR=/path ./gradlew :core:audio:testDebugUnitTest --tests '*RenderSamples*'`
    — 20 s WAV of every sound for listening / spectral checks (run in a scratch
    checkout; filtered test runs poison Kover data, see wellness notes)
- Kover excludes device-only glue (service, engine sink, focus gate,
  DataStore wrapper, Composables, DI modules, theme); everything else counts.
- Emulator workflow: `/adb-connect`, `/adb-deploy` (`dev.jtiisto.noise/.MainActivity`).
  Without an emulator, ship the APK: `rclone copyto app/build/outputs/apk/release/app-release.apk "gdrive:Hush/APKs/hush-<yyyymmdd-hhmm>.apk"`
  and/or `/personal-share`.

## Dev Environment
- Build machine: Linux (JDK 21, `~/android-sdk`, platforms 35–37).
- `local.properties` (untracked): `sdk.dir`, plus release signing keys
  `hush.keystore`, `hush.keystorePassword`, `hush.keyAlias`, `hush.keyPassword`
  (see `docs/release.md`). Missing keys → release build is unsigned.

## Key Design Decisions
- All sounds synthesized (no recordings): zero assets, never loops, tiny APK.
- One process-wide `PlaybackController` is the single source of truth; the
  foreground service and media session only mirror it.
- Max 3 simultaneous layers (CPU + UI clarity on small phones).
- Per-generator loudness calibration to −20 dBFS RMS.
- Resume after process death when the app was playing (persisted wall-clock
  timer end).
