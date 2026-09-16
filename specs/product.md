# Hush — product spec

## Goal
A calm, attractive Android sleep-sound app that plays layered noise and
procedurally synthesized ambience (rain, ocean, wind, fire…) reliably all night
on modest hardware. Side-loaded today, with a Google Play listing planned
(`docs/release.md` holds the parked runbook) — no accounts, no network, no
analytics.

App name: **Hush**. Application ID and base package: `dev.tapio.hush`.
Repo/Gradle root: `noise` (the working title; the ID is what ships).

## Target hardware
Mid/low-end phones, not flagship Pixels. Consequences that are NOT optional:
- minSdk 26 (Android 8.0). targetSdk 36, compileSdk 37.
- Phones first, but tablets, foldables and landscape are real: Android 16
  ignores orientation locks on screens 600 dp and wider, so every width has
  a layout (see `specs/ui.md`, **Layout**). Phone layouts stay unchanged.
- Audio is synthesized in pure Kotlin on one thread with no allocation in the
  render loop; a 3-layer mix must stay well under 5 % of one core.
- UI animations are few, cheap and GPU-friendly (gradients, scale/alpha
  tweens). No blur/RenderEffect, no per-frame Canvas visualizers, no
  particle systems.
- Release build is R8-minified with resource shrinking. No bundled audio
  files; every sound is generated.

## Sounds (all synthesized — see `specs/audio-engine.md`)
| Category | Sound | Notes |
|---|---|---|
| Noise | White, Pink, Brown, Blue, Violet, Grey | Classic colours; Grey = psychoacoustically flat |
| Nature | Rain, Downpour, Thunderstorm, Ocean, Wind, Campfire, Stream, Crickets | Procedural, never loops |
| Ambience | Fan, Airplane cabin | Steady mechanical beds |

Up to **3 sounds** play at once, each with its own volume. Curated **scenes**
(one-tap mixes) ship built in.

## Core features (v1, all required)
1. **Catalog + mixer** — tap a sound to add it to the mix (max 3), per-layer
   volume, master volume, one-tap scenes, clear mix.
2. **Background playback** — foreground service with a media-style
   notification and lock-screen/headset controls (Media3 session). Survives
   screen off, app swipe-away keeps playing; notification/stop ends it.
3. **Sleep timer** — 15/30/45/60/90/120 min presets + custom (5–480 min),
   live countdown, gentle fade-out over a configurable window (default 45 s),
   then playback stops and the service exits.
4. **Fades** — fade-in on play (1.5 s), fade-out on pause (0.6 s), layers
   crossfade when added/removed, all volume changes smoothed (no zipper noise).
5. **Audio focus** — pause on loss, duck on transient duck, resume after
   transient loss; pause when headphones unplug. Setting: *Mix with other
   apps* (skip focus entirely so rain can sit under an audiobook).
6. **Persistence** — last mix, per-layer volumes, master volume, settings and
   last timer choice survive restarts. If the process is killed while playing,
   the service restarts and resumes (mix + remaining timer, from persisted
   wall-clock end time).
7. **Attractive UI** — see `specs/ui.md`. Dark, night-first design that tints
   itself with the sounds in the mix.
8. **Large screens** — one adaptive layout for widths of 600 dp and more
   (tablets in either orientation, unfolded foldables, landscape phones): the
   playback pane and the catalog pane side by side. Nothing else changes.

## Explicit non-goals (v1)
- Real recordings (may come later through the same engine as a sample source).
- Alarm/wake-up, binaural beats, guided meditation, cloud sync, widgets.

## Quality bar
- Unit tests for every non-glue class; Kover aggregated line coverage gate in
  the pre-push hook (see `CLAUDE.md`).
- Compose Preview screenshot tests for the main screen states, rendered on
  the JVM (no emulator needed).
- Offline WAV render of every sound for spectral inspection
  (`NOISE_RENDER_DIR`, see `specs/audio-engine.md`).
- Codex full-repo review before the first tagged build; all findings fixed or
  explicitly accepted in `docs/reviews.md`.
