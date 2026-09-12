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
  Gate `minBound(90)` in the root `build.gradle.kts` (baseline 93.5 % measured
  2026-09-12). Read the true number by momentarily raising the bound to 100
  and reading the violation message — the XML report ignores the
  `@Composable` filter and under-reports.
- **Kover gotcha (bit us 2026-09-12):** module-scoped runs such as
  `./gradlew :core:audio:testDebugUnitTest` write execution data without the
  root filters, and a later `koverVerifyAggregated` then reports a wrong,
  lower number (89.6 % vs the real 93+ %) and fails the pre-push gate in one
  second. Recovery: delete `build/kover */build/kover */*/build/kover
  */build/tmp/koverCachedVerify* */*/build/tmp/koverCachedVerify*
  */build/test-results/testDebugUnitTest */*/build/test-results/testDebugUnitTest`
  and re-run `./gradlew testDebugUnitTest koverVerifyAggregated --no-build-cache --rerun-tasks`.
  Prefer the unscoped `./gradlew testDebugUnitTest` before any push.
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

## On-device smoke test (required before shipping)
A headless emulator runs on the build server itself, so a real launch is part
of the release gate now (a foreground-service crash shipped in 0.1.0 because no
build had ever been run on a device — see docs/reviews.md).
- KVM works but the user is not in the `kvm` group by default; run the emulator
  through `sg kvm -c "..."`.
- Setup (one-time): `sdkmanager "emulator" "system-images;android-35;google_apis;x86_64"`,
  then `avdmanager create avd -n hush_test -k "system-images;android-35;google_apis;x86_64" -d pixel_6a`.
- Boot headless: `sg kvm -c "$ANDROID_SDK/emulator/emulator -avd hush_test -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot"`.
- Drive the UI over ADB with uiautomator dumps (tap by content-desc); read
  crashes with `adb logcat -b crash`. Smoke path: launch, add a sound, play,
  allow notifications, add a third sound, confirm `isForeground=true` and no
  FATAL, swipe away and reopen, start a short timer and let it fade out.

## Current Status (2026-09-12)
v0.1.0 complete and reviewed: 16 synthesized sounds, 3-layer mixer, scenes,
sleep timer with fade and an explicit until-cancelled mode, Media3 foreground
playback with lock-screen controls, audio focus + becoming-noisy handling,
persistence with resume after process death. 361 unit tests (model 7, audio
143, playback 83, app 98 + 12 screenshot references), Kover gate 90 (baseline
93.5 %). Codex review #1 findings all fixed (`docs/reviews.md`). Release APK
1.7 MB, signed with the local keystore. Exercised on a headless API 35 emulator — first on-device checks to do: notification/lock-screen controls,
headphone unplug, timer fade at the end, resume after force-stop, and the
subjective sound quality of stream/thunder/rain (see `docs/sound-design.md`
for the tuning knobs).

