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
`AudioTrackEngine(config: EngineConfig = EngineConfig())` implements it, and
builds its `MixRenderer` at the device's *native* output rate
(`AudioTrack.getNativeOutputSampleRate`) so the framework never resamples us.
`MixRenderer(config, seed = DEFAULT_SEED, factory = Generators)` is the
pure-Kotlin core the engine pumps; tests drive `MixRenderer` directly and can
inject a deterministic `GeneratorFactory`. It holds `2 * Mix.MAX_LAYERS` slots
— `MAX_LAYERS` sounding plus the same number still fading out — so replacing a
full mix crossfades instead of cutting; re-adding a sound that is still fading
revives its slot rather than allocating a second one for the same generator.

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
  Crickets are the one exception at **-26 dBFS ± 1.5 dB** (tonal content at
  equal RMS sounds much louder). Peaks are typically -8 to -3 dBFS; the
  low-frequency sounds (ocean swell, campfire pops) reach ~-2.4 dBFS, because
  Gaussian low-frequency content genuinely peaks at 4 sigma. The gate is
  peak ≤ 0.9 so three layers still fit in the headroom.
- Mix: `sum(layerGain(id) * generator) * master * fadeEnv * duckEnv`, then a
  cheap soft clipper (input clamped to ±3, `x*(27+x²)/(27+9x²)`, output
  clamped to ±1 — the rational form reaches exactly ±1 at ±3 and
  single-precision rounding can otherwise land a hair outside). Layer gain maps the
  UI slider 0..1 to amplitude with `slider²` (perceptual). A layer removed from
  the mix fades to 0 over `layerCrossfadeMs` and is then dropped; added layers
  fade in. Master volume slews over 20 ms and the duck over 120 ms, applied
  **per sample** (not per block) so no instantaneous gain step ever reaches the
  output — measured worst sample-to-sample delta during a full mix swap is
  2.3e-5. Ramps count samples down rather than comparing against the target, so
  they land on it exactly and the renderer can drop back to its constant-gain
  fast path.
- Fade curves for start/stop/sleep-timer are equal-power (sin²) over their
  duration, evaluated per sample from a 4096-point table with exact 0/1
  endpoints (a `sin` per sample per fading layer is measurable on a low-end
  phone; the table is not).
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
  16 kHz to tame the top. The differencer's own `2·sin(πf/fs)` shape plus the
  tilt filter cost ~0.15 dB/oct across the 200 Hz–8 kHz measurement band, so
  the measured slope lands near +2.9 rather than exactly +3.
- **Violet** — first difference of white (+6 dB/oct), same LP.
- **Grey** — white through an inverse-A-weighting approximation (a low shelf
  +12 dB @ 120 Hz, a dip −4 dB @ 3 kHz, a high shelf +6 dB @ 10 kHz as
  biquads), i.e. perceptually flat.

Nature:
- **Rain** — three layers. (1) bed: Gaussian-ish noise → band-pass
  1.2–6 kHz (2 biquads) with a slow random-walk amplitude (~0.12 Hz) for
  gusts; (2) drops: Poisson events (≈ 60/s light, 200/s downpour), each a
  2–12 ms decaying burst of noise through a 1-pole LP at a random 2–9 kHz,
  random amplitude, random pan, ≤ 24 simultaneous voices (voice stealing);
  (3) distant body: brown noise HP 45 Hz → LP 700 Hz at low level.
  **Downpour** = same generator with a heavier preset (denser drops, more low
  body, sheet widened to 0.8–8 kHz).
  The bed's lower corner and the body's low-pass are wider than the original
  1.5 kHz / 300 Hz: octave-band measurement of the offline renders showed a
  ~20 dB hole between 250 Hz and 1 kHz with those values, which reads as
  hollow. The body is high-passed at 45 Hz because content below that is
  inaudible on a phone speaker and only costs headroom.
- **Thunderstorm** — Downpour bed (trimmed to 0.9) + thunder events every
  25–90 s (uniform random), with the *first* event deliberately 5–15 s after
  start so the sound identifies itself and the 20 s offline render contains a
  roll: a 4–9 s brown-noise burst through LP 40–220 Hz with a sharp attack
  (30–150 ms), a long exponential decay, and 1–3 sub-rolls (overlapping
  difference-of-exponentials bumps, summed and clamped); peak limited so a
  roll never exceeds the rain bed by more than +6 dB (sleep app — no jump
  scares). Optional "crack" transient only on 1 in 4 rolls, 9 dB under the
  roll's own peak.
- **Ocean** — swell envelope: raised-cosine, period 9–15 s randomized per
  wave, asymmetric (2/5 attack, 3/5 decay), with a 0.07 floor so the sea never
  goes silent; layers: brown (HP 40 Hz) + 40 % pink body following the
  envelope, "foam" = white → BP 1–3 kHz that follows the envelope delayed
  0.4 s and squared (crest hiss), plus a constant distant wash at −20 dB. A
  1-pole LP whose cutoff tracks the envelope (600 Hz → 4 kHz, retuned at
  control rate) so crests are brighter.
- **Wind** — two voices: howl = white → resonant BP (centre 250–700 Hz random
  walk at ~0.1 Hz, Q≈4) and whistle = white → BP 1.2–2.5 kHz at −12 dB with
  its own walk; a shared gust envelope (random walk with occasional 3–8 s
  swells) multiplies both. Pan drifts slowly ±0.3.
- **Campfire** — rumble: brown (HP 55 Hz) → LP 120 Hz with slow flutter;
  crackles: Poisson 4–12/s (the rate itself drifts slowly across that range),
  each 3–25 ms burst through a resonant BP at 900 Hz–5 kHz, random pan and
  amplitude, occasional (1 in 15) louder "pop"; hiss: white → BP 3–7 kHz with
  fast flutter (~8 Hz filtered-noise AM). The rumble/crackle/hiss balance is
  set from octave-band measurement rather than from relative dB alone: with
  the crackles 20 dB under the sub-60 Hz rumble they are inaudible on a phone
  speaker.
- **Stream** — 6 resonators (BP, Q 6–12) spread geometrically 400 Hz–5 kHz,
  each fed by white noise, with independent fast amplitude wobble (6–14 Hz
  filtered-noise AM, depth 40 %) and slow centre-frequency drift ±8 %; plus a
  broadband wash (white → LP 2 kHz) at −10 dB. Panned ±0.5 per resonator,
  alternating outward from the centre rather than sweeping with frequency (a
  frequency-ordered sweep combined with the bank's downward tilt unbalances
  the channels). Each resonator has its *own noise stream and filter per
  channel*: one mono resonator fanned out through pan gains leaves the whole
  bank ~0.9 correlated.
- **Crickets** — 4 individuals: carrier sine 3.6–5.2 kHz (wavetable), AM
  trill 28–42 Hz (depth 100 %), chirp gate 80–160 ms on / 150–350 ms off with
  a 4 ms one-pole smoothing so the gate does not click, chirp bursts of 3–7
  with 1–4 s pauses, each with its own pan and level; plus a pink bed at
  −24 dB, high-passed at 250 Hz (a full-range pink bed puts most of its energy
  below 200 Hz, where a summer field has none). Overall calibrated softer
  (−26 dBFS) because it is tonal; its L/R correlation is also the highest in
  the catalog (~0.85), correctly so — four crickets are four point sources.

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
writes 16-bit stereo WAVs to `$NOISE_RENDER_DIR/<sound-key>.wav` when that env
var is set, plus `mix_rain_brown.wav` (rain 0.7 + brown 0.7); otherwise the
test is skipped via `assumeTrue`. Rendering goes **through `MixRenderer`**, so
the files include the slider mapping and the soft clipper — the same signal the
user hears. A 3 s settle is discarded first so the file is 20 s of the steady
sound rather than 20 s that starts with the fade-in.

This is how the sounds are inspected without a device and how sample files are
handed to the user. Three Python views are worth plotting, and each caught a
real defect: a log-frequency spectrogram (drop/crackle/chirp texture), a
short-window RMS envelope (swells, gusts, rolls, phrases), and **octave-band
levels** — the last is essential, because a per-hertz spectrogram makes every
brown-noise bed look bass-heavy and hides both a midrange hole and an
inaudible crackle layer. See `docs/sound-design.md`.

## Tests (JVM, JUnit 5)
- Every generator: no NaN/Inf, |sample| ≤ 1 (and ≤ 0.9 for mix headroom), RMS
  within its target ± 1 dB (crickets ± 1.5) after a 2 s warm-up, measured over
  12 s for the steady beds and longer where the slowest feature is longer
  (20 s wind/campfire, 25 s crickets, 40 s thunderstorm, 60 s ocean).
  `reset(seed)` is reproducible. L/R correlation < 0.9 (stereo actually
  decorrelated) except where discrete events dominate — rain, downpour,
  thunderstorm, campfire and crickets use < 0.95.
- Spectral slope checks with a small radix-2 FFT in test code: white ≈ 0,
  pink ≈ −3, brown ≈ −6, blue ≈ +3, violet ≈ +6 dB/oct (±1 dB) measured between
  200 Hz and 8 kHz on Welch-averaged periodograms.
- Rain: drop event rate within ±20 % of preset. Ocean: envelope period within
  range, min/max ratio > 12 dB. Crickets: dominant peak in 3.5–5.3 kHz plus a
  chirp duty cycle that proves the phrases pause. Thunderstorm: 0.5–2.6 rolls
  per minute and a peak no more than 8 dB over the median bed. Campfire:
  crackle rate inside the preset range. Wind and stream: dominant peak inside
  the designed band. Downpour: measurably fuller low end than light rain.
- Every DSP primitive has its own tests: RNG range/mean/variance/seeding,
  measured biquad magnitude responses and stability at extreme settings,
  one-pole and leaky-integrator RMS gains against their closed forms,
  wavetable sine accuracy, control-signal bounds, Poisson rate, envelope
  endpoints and timing, voice-pool lifecycle/panning/stealing/reset.
- Performance: 60 s of a three-layer thunderstorm+ocean+campfire mix renders in
  about 1.1 s on the dev machine (≈ 55x real time on one JVM thread); the test
  asserts a generous 15 s ceiling and prints the measured figure.
- `MixRenderer`: gain mapping, layer add/remove crossfade continuity (no
  sample-to-sample jump > 0.05 during crossfades), soft clipper bounds, fade
  envelopes reach exactly 0/1, duck ramps, sleep fade calls `onComplete`
  exactly once.
- `AudioTrackEngine` is device glue: excluded from coverage, kept thin.
