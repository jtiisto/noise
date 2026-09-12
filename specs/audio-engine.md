# Audio engine spec (`core/audio`)

## Goal
Generate every sound in the catalog procedurally, mix up to 3 of them with
smooth gain control, and push the result to `AudioTrack` from one dedicated
thread with zero allocation in the render loop. All DSP is pure Kotlin and
unit-testable on the JVM; only the `AudioTrack` sink touches Android.

## Public API (`dev.jtiisto.noise.core.audio`)
```kotlin
interface AudioEngine {
    val isRunning: Boolean
    fun start()                                   // idempotent; fade-in from silence
    fun stop()                                    // short fade-out, then release; idempotent
    fun setMix(mix: Mix)                          // crossfades layers in/out
    fun setMasterVolume(volume: Float)            // 0..1, smoothed
    fun setDucked(ducked: Boolean)                // transient duck (e.g. notification sound)
    fun beginFadeOut(durationMs: Long, onComplete: () -> Unit)  // sleep-timer fade; then stop()
    fun cancelFadeOut()                           // restores gain smoothly, keeps playing
}
```
`AudioTrackEngine(config: EngineConfig)` implements it. `MixRenderer` is the
pure-Kotlin core the engine pumps; tests drive `MixRenderer` directly.

```kotlin
data class EngineConfig(
    val sampleRate: Int = 48_000,
    val blockFrames: Int = 2048,        // ~43 ms; large blocks = fewer wakeups = battery
    val fadeInMs: Int = 1500,
    val fadeOutMs: Int = 600,
    val layerCrossfadeMs: Int = 400,
    val duckGain: Float = 0.2f,
)
```

## Rendering model
- Float32 stereo, non-interleaved `FloatArray` L/R per block, converted to
  interleaved float for `AudioTrack` (`ENCODING_PCM_FLOAT`, `USAGE_MEDIA`,
  `CONTENT_TYPE_MUSIC`, `MODE_STREAM`, buffer = 4× `getMinBufferSize`).
- Every generator implements
  `interface SoundGenerator { fun render(left: FloatArray, right: FloatArray, frames: Int); fun reset(seed: Long) }`
  and is constructed with `sampleRate`. Generators own all their state; no
  allocation inside `render`. Inner loops use local vals, not fields.
- Stereo: L and R use independent noise streams but SHARED event/modulation
  state (a wave crest, a gust, a thunder roll happens on both sides). Discrete
  events (raindrops, crackles, chirps) are individually panned.
- Loudness: each generator is calibrated so that at gain 1.0 its long-term
  RMS is **-20 dBFS ± 1 dB** (per channel) so switching sounds never jumps.
  Peaks may reach -3 dBFS.
- Mix: `sum(layerGain(id) * generator) * master * fadeEnv * duckEnv`, then a
  cheap soft clipper (`x*(27+x²)/(27+9x²)` clamped to ±1). Layer gain maps the
  UI slider 0..1 to amplitude with `slider²` (perceptual). A layer removed from
  the mix fades to 0 over `layerCrossfadeMs` and is then dropped; added layers
  fade in. Master/duck/fade envelopes are linear ramps per block (~20 ms) —
  no instantaneous gain steps ever reach the output.
- Fade curves for start/stop/sleep-timer are equal-power (sin²) over their
  duration, evaluated per block.
- Thread: a plain `Thread` named `hush-audio` with
  `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)`. Parameters
  cross into it through a single `AtomicReference<MixSnapshot>` read once per
  block (no locks, no queues in the hot path). `stop()` sets a flag, the
  thread finishes the fade, flushes, releases the track. Underruns are counted
  (`AudioTrack.getUnderrunCount`) and logged; on `ERROR_DEAD_OBJECT` the track
  is recreated once.
- RNG: xorshift128+ (or PCG) with per-generator seeds; `nextFloat()` in [-1,1).
  White noise is uniform (flat spectrum is what matters). Where a Gaussian
  shape matters (rain bed, ocean) sum three uniforms.

## Generators — design notes (Farnell-style synthesis; tune by ear-proxy = spectra)
Colours (`NoiseColor`):
- **White** — uniform noise.
- **Pink** — Paul Kellet's refined 7-pole filter (≈ −3 dB/oct, ±0.05 dB
  10 Hz–20 kHz).
- **Brown** — leaky integrator of white (cutoff ≈ 8 Hz) followed by a 1-pole
  high-pass at 25 Hz (kills DC/rumble); −6 dB/oct.
- **Blue** — first difference of pink (+3 dB/oct), then gentle 1-pole LP at
  16 kHz to tame the top.
- **Violet** — first difference of white (+6 dB/oct), same LP.
- **Grey** — white through an inverse-A-weighting approximation (a low shelf
  +12 dB @ 120 Hz, a dip −4 dB @ 3 kHz, a high shelf +6 dB @ 10 kHz as
  biquads), i.e. perceptually flat.

Nature:
- **Rain** — three layers. (1) bed: Gaussian-ish noise → band-pass 1.5–6 kHz
  (2 biquads) with a slow random-walk amplitude (0.05–0.2 Hz) for gusts; (2)
  drops: Poisson events (≈ 60/s light, 200/s downpour), each a 2–12 ms decaying
  burst of noise through a 1-pole LP at a random 2–9 kHz, random amplitude,
  random pan, ≤ 24 simultaneous voices (voice stealing); (3) distant body:
  brown noise LP 300 Hz at low level. **Downpour** = same generator with a
  heavier preset (denser drops, more low body, brighter bed).
- **Thunderstorm** — Downpour bed + thunder events every 25–90 s (uniform
  random): a 4–9 s brown-noise burst through LP 40–220 Hz with a sharp attack
  (30–150 ms), a long exponential decay, and 1–3 sub-rolls; peak limited so a
  roll never exceeds the rain bed by more than +6 dB (sleep app — no jump
  scares). Optional "crack" transient only on 1 in 4 rolls.
- **Ocean** — swell envelope: raised-cosine, period 9–15 s randomized per
  wave, asymmetric (2/5 attack, 3/5 decay); layers: brown/pink body following
  the envelope, "foam" = white → BP 1–3 kHz that follows the envelope
  delayed 0.4 s and squared (crest hiss), plus a constant distant wash at −18
  dB. A 1-pole LP whose cutoff tracks the envelope (600 Hz → 4 kHz) so crests
  are brighter.
- **Wind** — two voices: howl = white → resonant BP (centre 250–700 Hz random
  walk at ~0.1 Hz, Q≈4) and whistle = white → BP 1.2–2.5 kHz at −12 dB with
  its own walk; a shared gust envelope (random walk with occasional 3–8 s
  swells) multiplies both. Pan drifts slowly ±0.3.
- **Campfire** — rumble: brown → LP 120 Hz with slow flutter; crackles:
  Poisson 4–12/s, each 3–25 ms burst through a resonant BP at 900 Hz–5 kHz,
  random pan and amplitude, occasional (1 in 15) louder "pop"; hiss: white →
  BP 3–7 kHz at −16 dB with fast flutter (5–12 Hz random AM). 
- **Stream** — 6 resonators (BP, Q 6–12) spread 400 Hz–5 kHz fed by white
  noise, each with independent fast amplitude wobble (6–14 Hz random AM,
  depth 40 %) and slow centre-frequency drift ±8 %; plus a broadband wash
  (white → LP 2 kHz) at −10 dB. Panned ±0.5 per resonator.
- **Crickets** — 4 individuals: carrier sine 3.6–5.2 kHz, AM trill 28–42 Hz
  (depth 100 %), chirp gate 80–160 ms on / 150–350 ms off, chirp bursts of
  3–7 with 1–4 s pauses, each with its own pan and level; plus a pink bed at
  −24 dB. Overall calibrated softer (−26 dBFS) because it is tonal.

Ambience:
- **Fan** — pink → LP 1.8 kHz with a broad resonance at 400 Hz (Q 0.7,
  +4 dB), a 4 % AM at blade frequency 28 Hz, and a 110 Hz hum at −30 dB.
- **Airplane** — brown → LP 900 Hz with a +6 dB peak at 180 Hz (Q 1.2) plus
  white → BP 2–4 kHz at −18 dB for the air-vent hiss; very slow (0.02 Hz)
  ±1 dB drift so it breathes.

Every generator has a `Preset`-style constructor parameter object so tests and
the offline renderer can tweak densities without touching catalog defaults.

## Offline render harness
`RenderSamplesTest` (JUnit) renders 20 s of each `SoundId` at gain 1.0 and
writes 16-bit stereo WAVs to `$NOISE_RENDER_DIR/<sound-id>.wav` when that env
var is set; otherwise the test is skipped via `assumeTrue`. This is how the
sounds are inspected without a device (spectrograms in Python) and how sample
files are handed to the user.

## Tests (JVM, JUnit 5)
- Every generator: no NaN/Inf, |sample| ≤ 1, RMS within −20 ± 1.5 dBFS after a
  2 s warm-up, L/R correlation < 0.9 (stereo actually decorrelated) except where
  events dominate (rain/fire use < 0.95).
- Spectral slope checks with a small radix-2 FFT in test code: white ≈ 0,
  pink ≈ −3, brown ≈ −6, blue ≈ +3, violet ≈ +6 dB/oct (±1 dB) measured between
  200 Hz and 8 kHz on Welch-averaged periodograms.
- Rain: drop event rate within ±20 % of preset. Ocean: envelope period within
  range, min/max ratio > 12 dB. Crickets: dominant peak in 3.5–5.3 kHz.
- `MixRenderer`: gain mapping, layer add/remove crossfade continuity (no
  sample-to-sample jump > 0.05 during crossfades), soft clipper bounds, fade
  envelopes reach exactly 0/1, duck ramps, sleep fade calls `onComplete`
  exactly once.
- `AudioTrackEngine` is device glue: excluded from coverage, kept thin.
