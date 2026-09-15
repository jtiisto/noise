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
  low-frequency sounds (ocean swell) reach ~-2.4 dBFS, because Gaussian
  low-frequency content genuinely peaks at 4 sigma. The campfire's bright cracks
  are peaky too (crest ~27 dB before limiting) and are held to ~-1.3 dBFS by a
  soft limiter (see docs/sound-design.md). The gate is peak ≤ 0.9 so three
  layers still fit in the headroom.
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
  (3) **close drops**: a second, much sparser Poisson stream (4.5/s light,
  11/s downpour) at ~8× the sheet drops' amplitude, each a 20–50 ms burst
  through a resonant BP (1.5–4 kHz, Q 8–15), individually panned — the drops
  landing within a couple of metres, which give the shower a foreground the
  ear can resolve; (4) distant body: brown noise HP 45 Hz → LP 700 Hz at low
  level.
  **Downpour** = same generator with a heavier preset (denser drops, more low
  body, sheet widened to 0.8–8 kHz).
  The bed's lower corner and the body's low-pass are wider than the original
  1.5 kHz / 300 Hz: octave-band measurement of the offline renders showed a
  ~20 dB hole between 250 Hz and 1 kHz with those values, which reads as
  hollow. The body is high-passed at 45 Hz because content below that is
  inaudible on a phone speaker and only costs headroom.
- **Thunderstorm** — its own darker, heavier `STORM` rain bed (trimmed to 0.9),
  *not* the Downpour preset, + prominent thunder events every 15–45 s (first at
  5–15 s so the sound identifies itself), ~1–3/min. A roll is three layers under
  one 2–4-bump difference-of-exponentials envelope (over the first 60 % of a
  5–11 s event, so it *rolls* rather than thumps): (1) a **deep rumble** — brown
  noise through LP 60–400 Hz sweeping *down* to 45 % (darkens as it decays), at
  `peakAmplitude` 0.38, which leads on real low-end speakers (~+16 dB over the
  bed in the sub-250 Hz band); (2) a **low-mid onset body** — a ~720 Hz noise
  band mixed in but *faded out over the roll*, so a phone speaker (which cannot
  reproduce the deep rumble at all) gets a cue in the 400 Hz–1.3 kHz range at the
  strike, while the tail stays a pure darkening rumble; (3) an onset **crack** on
  ~70 % of rolls (1.2–2.6 kHz). The rain bed **ducks 25 %** under the roll so the
  thunder sits forward. Because a prominent roll + bed + crack can sum past 1.5,
  the generator carries **its own soft limiter** (tanh, knee 0.62, ceiling 0.88):
  a no-op below the knee (bed and ordinary rolls), so it only shaves the rare
  peak, keeps the output ≤ 0.9, and never lets the downstream clipper distort a
  roll. This is an event-driven sound, so it is calibrated on the **between-rolls
  bed** (median of the RMS envelope) to −20 dBFS, with rolls as events above it,
  not on the roll-inflated mean; the test asserts a roll is clearly audible
  (≥ 8 dB over the bed) and never clips (peak ≤ 0.9). A roll renders for 1.6× its
  nominal duration (`TAIL_FACTOR`) so the last sub-roll decays fully instead of
  being cut mid-tail (a click); the inter-roll gap counts from the extended end.
- **Ocean** — swell envelope: raised-cosine, period 9–15 s randomized per
  wave, asymmetric (2/5 attack, 3/5 decay), with a 0.07 floor so the sea never
  goes silent; layers: brown (HP 40 Hz) + 40 % pink body following the
  envelope, "foam" = white → BP 1–3 kHz that follows the envelope delayed
  0.4 s and squared (crest hiss), plus a constant distant wash at −20 dB. A
  2nd-order LP whose cutoff tracks the envelope (600 Hz → 4 kHz, retuned at
  control rate) so crests are brighter. Second order rather than one pole:
  6 dB/oct from 4 kHz still leaves the body's pink component audible at
  15 kHz, and crests came out hissy above the 1–3 kHz band the foam owns.
- **Wind** — three voices: howl = white → resonant BP (centre 250–700 Hz
  random walk at ~0.1 Hz, Q≈4); whistle = white → BP 1.2–2.5 kHz at −12 dB
  with its own walk; and buffet = brown noise HP 25 Hz → LP 90 Hz gated by the
  gust envelope *squared* (and capped — brown noise has a crest factor near 4
  and an uncapped square of a 1.8 gust reaches full scale on its own). A
  shared gust envelope (random walk with occasional 3–8 s swells) multiplies
  the howl and whistle; the buffet is what gives a gust weight rather than
  just more hiss, and it lifts the 30–80 Hz band ~12 dB during a swell. Pan
  drifts slowly ±0.3 and is applied to the howl and whistle only — below
  ~100 Hz the ear cannot localise, and panning the buffet would unbalance the
  channels as the image drifts.
- **Campfire** — rumble: brown (HP 55 Hz) → LP 120 Hz with slow flutter, a
  low supporting bed; hiss: white → BP 2.5–9 kHz with fast flutter (~8 Hz
  filtered-noise AM), the continuous sizzle; crackles: three impulsive event
  types emitted in irregular *flurries* (each Poisson trigger, at a rate that
  drifts across the preset range, is a head that spawns 0–3 rapid follow-ons),
  each with a *near-instant* attack (~0.1–0.35 ms, not the beds' soft
  difference-of-exponentials rise) and randomised level/decay/brightness/pan:
  **bright snaps** (the majority) — broadband white, gently HP ~1.1 kHz then LP
  3–7 kHz, decay 2–9 ms, *no resonance* (a real crack is a broadband click, not
  a pitched ring); **mid crackles** — dimmer, 0.6–3.5 kHz; **low woody pops**
  (occasional) — a resonant thump at 150–480 Hz (Q 1.5–3.5), the body that says
  "logs" rather than a merely louder crack. This replaces the earlier single
  resonant-BP crackle (900 Hz–5 kHz, Q 5–14) on the beds' soft envelope, which
  measured soft, dull, pitched and uniform and read as artificial/rain-like. A
  bright fire is inherently peaky; the beds stay low (so cracks are not swamped)
  and a gentle soft limiter holds the peak under the 0.9 ceiling without dulling
  the cracks' onset. The crackle character is tuned against real fire recordings
  (see docs/sound-design.md and `research/compare_fire.py`), not by octave-band
  balance alone.
- **Stream** — 6 bubble *size classes* (BP, Q 3–5) spread geometrically
  400 Hz–5 kHz, each excited by its **own Poisson stream of 30–70 short noise
  bursts per second** (difference-of-exponentials, 15–60 ms, attack 1/5 of the
  decay, triggers summed into the envelope state so overlaps add correctly),
  with a per-burst upward pitch sweep (the centre is pulled 35 % down at onset
  and climbs back as the sweep decays — a rising bubble) and a resting-pitch
  random walk of ±20 % at 0.3–1 Hz; plus a broadband wash (white → LP 2 kHz)
  at −10 dB. Panned ±0.5 per resonator, alternating outward from the centre
  rather than sweeping with frequency (a frequency-ordered sweep combined with
  the bank's downward tilt unbalances the channels). Each resonator has its
  *own noise stream and filter per channel* under a shared envelope: one mono
  resonator fanned out through pan gains leaves the whole bank ~0.9
  correlated.
  This replaces a first version that used *continuous* noise with a 6–14 Hz
  amplitude wobble and Q 6–12. That version measured correctly and sounded
  wrong: the spectrogram was six straight horizontal lines for twenty seconds,
  which the ear reads as a filtered drone, because water has no sustained
  partials — it has onsets. Levels are normalised by both the band-pass noise
  gain and the Poisson train's mean square (∝ rate × τ) so rate, duration, Q
  and centre shape texture and not loudness.
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
- Stream: bubble rate within the preset's range, and — the test that would
  have caught the drone — the 5 ms envelope's p95/p50 must exceed that of the
  *same generator with its Poisson clocks driven fast enough to be
  continuous*, by at least 1.5 dB. That control is the old design, so the
  assertion isolates the impulsive excitation and needs no absolute threshold.
- Rain: drop event rate within ±20 % of preset. Ocean: envelope period within
  range, min/max ratio > 12 dB. Crickets: dominant peak in 3.5–5.3 kHz plus a
  chirp duty cycle that proves the phrases pause. Thunderstorm: 0.5–2.6 rolls
  per minute and a peak no more than 8 dB over the median bed. Campfire:
  crackle rate inside the preset range, and — the assertion that would have
  caught the soft/pitched/rain-like crackle — the cracks are impulsive and
  broadband: per-crack 10–90 % attack p10 < 0.5 ms and median < 1 ms, spectral
  flatness median > 0.12 (far above the old resonant crackle's ~0.03), centroid
  median > 3 kHz and a wide centroid spread, measured on the un-limited crack
  shape with the shared FFT utilities. Wind and stream: dominant peak inside
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
