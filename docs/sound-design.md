# Sound design notes

How each of Hush's sixteen sounds is synthesized, what its tuning knobs are,
and how the whole catalog is held at one loudness. The contract lives in
`specs/audio-engine.md`; this document is the *why*, written for the next
person who has to change a sound without breaking it.

The reference for the techniques is Andy Farnell, *Designing Sound*. The
recurring idea is that a natural texture is almost never one signal: it is a
continuous bed, a stream of discrete events, and a slow modulation that ties
them together. Get any of the three wrong and the ear immediately classifies
the result as "synthesizer".

Every generator lives in `core/audio/.../generators/` and takes a `*Preset`
data class, so densities, bands and levels can be changed from a test or the
offline renderer without touching catalog defaults.

## Loudness calibration

Every generator multiplies its output by one measured constant so that its
long-term RMS at unity gain is **-20 dBFS per channel**. Crickets are the sole
exception at **-26 dBFS**: they are narrow-band and tonal, and tonal content at
equal RMS sounds far louder than a broadband bed.

The constants are *measured, not derived*. The procedure, which is also the
test (`GeneratorCalibrationTest`):

1. Render the generator offline at 48 kHz, discard a 2 s warm-up so filters and
   random walks have settled.
2. Measure RMS over a window long enough to average the sound's slowest
   feature — 12 s for the steady beds, 20 s for wind and campfire, 25 s for
   crickets' phrases, 40 s for thunderstorm's rolls, 60 s for the ocean's
   9-15 s swells. A short window on an event-driven sound measures luck.
3. Multiply the generator's `outputGain` by `10^((target - measured)/20)` and
   repeat. One iteration converges because the mapping is exactly linear.

The test asserts +/-1 dB (+/-1.5 dB for crickets) and prints the whole table, so
a synthesis change that shifts a level fails the build rather than surprising
someone at 3 a.m. It also asserts no NaN/Inf, a peak under 0.9 (three layers
must fit in the headroom), and that left/right really are decorrelated.

Two internal conventions keep the constants from drifting:
`BrownNoiseSource` is normalised to unit RMS from closed-form filter gains, and
`bandPassNoiseMakeup()` normalises any resonant band-pass, so randomising a
crackle's pitch or a resonator's Q changes timbre only, never level.

## The noise colours

**White** — uniform draws from the xorshift128+ RNG. Uniform rather than
Gaussian: "white" means a flat spectrum, which any zero-mean IID sequence has,
and the uniform draw is one multiply. Knobs: none. Gain 0.1732 (= 0.1 / RMS of
a uniform on [-1,1)).

**Pink** — Paul Kellet's refined seven-pole filter, six one-pole sections plus
a feed-through term. Flat to about +/-0.05 dB against a true -3 dB/oct from
10 Hz to 20 kHz. Its coefficients are defined at 44.1 kHz; at 48 kHz the pole
frequencies shift up 8.8 %, which costs about 0.2 dB of slope error across the
200 Hz-8 kHz measurement band — well inside tolerance, so the published
constants are kept rather than re-fitted per rate. Measured slope -3.00 dB/oct.

**Brown** — a leaky integrator at 8 Hz (white noise accumulating into a bounded
random walk) followed by a one-pole high-pass at 25 Hz. The high-pass is not
optional: without it the walk's DC term drifts, eats headroom and spends
speaker excursion on inaudible content. Measured slope -5.94 dB/oct. Knobs:
integrator and high-pass corners.

**Blue** — the first difference of pink (+6 dB/oct on top of -3), then a gentle
one-pole low-pass at 16 kHz so it is airy rather than abrasive. The
differencer's own `2·sin(pi·f/fs)` shape plus the tilt filter cost about
0.15 dB/oct at the top of the band; measured -> +2.86 dB/oct.

**Violet** — the first difference of white, same 16 kHz tilt. Measured
+5.91 dB/oct.

**Grey** — white through three biquads approximating inverse A-weighting: a
+12 dB low shelf at 120 Hz, a -4 dB dip at 3 kHz (Q 1.0) and a +6 dB high shelf
at 10 kHz. It measures tilted and *sounds* flat. Three biquads land within a
couple of dB of the exact inverse curve, which is inside the spread between
individual listeners' equal-loudness contours; a long FIR would be honest
overkill. Measured band levels: 80-160 Hz sits 10.4 dB above the 3 kHz dip, and
9-13 kHz sits 7.3 dB above it.

## Rain and Downpour

One generator, two presets. Three layers:

1. **The sheet** — Gaussian noise (three summed uniforms) band-passed by two
   biquads. This is the sum of the thousands of drops too far or too small to
   resolve, and by the central limit theorem that sum is Gaussian; flat-topped
   uniform noise in its place reads as "hiss" rather than "rain". A 0.12 Hz
   filtered random walk swings its amplitude +/-35 % so the shower breathes —
   the single strongest cue that a bed is *not* a loop.
2. **The drops** — a Poisson clock (exponential inter-arrival gaps, so the
   stream has real clusters and holes; a jittered grid never does) firing into
   a 24-voice pool. Each drop is a difference-of-exponentials burst of white
   noise through its own one-pole low-pass. Decay time, brightness, level and
   pan are all randomised independently; constant-timbre drops sound like a
   Geiger counter. Level uses a squared uniform so quiet drops dominate and the
   occasional loud one stands out.
3. **The body** — brown noise low-passed at 700 Hz and high-passed at 45 Hz:
   rain on ground and roofs a street away. Without it the sound has no depth
   and sits inside the listener's head; below 45 Hz it is pure wasted
   excursion.

Knobs (`RainPreset`): `dropsPerSecond`, sheet corners, per-layer levels, drop
duration and colour ranges, gust rate and depth, voice count, `outputGain`.

`LIGHT` is 60 drops/s over a 1.2-6 kHz sheet; `DOWNPOUR` is 200 drops/s over a
0.8-8 kHz sheet with twice the low body and quieter individual drops — past
roughly 200/s the ear stops resolving drops and hears a sheet, which is exactly
what heavy rain is, so the extra rate buys density rather than audible ticks.
Measured drop rates: 60.8/s and 203.9/s.

Deviation from the original spec: the sheet's lower corner is 1.2 kHz (Rain) /
800 Hz (Downpour) rather than 1.5/1.1 kHz, and the body's low-pass is 700 Hz
rather than 300 Hz. With the spec's numbers the octave-band plot showed a 20 dB
hole between 250 Hz and 1 kHz, which sounds hollow; the wider bands close it to
about 15 dB, which matches real rain's gentle 2-6 kHz emphasis.

## Thunderstorm

The downpour bed at 0.90 level plus rare rolls. A roll is modelled on how
thunder actually reaches a listener kilometres away: the discharge is
broadband, but air absorption and ground reflection strip everything above a
couple of hundred hertz, and the sound arrives smeared over seconds because
different parts of the channel are different distances away.

* Source: brown noise (already -6 dB/oct) through a second-order low-pass drawn
  per event between 40 and 220 Hz — near rolls are brighter.
* Envelope: 2-4 overlapping difference-of-exponentials bumps spread over the
  first 55 % of a 4-9 s event, summed and clamped to 1. The overlap is what
  gives a roll its re-swelling rather than a single decaying thud.
* One roll in four gets a short band-passed "crack" at onset (1.2-2.2 kHz,
  9 dB under the roll's own peak): the direct path arriving before the smeared
  reflections. It is deliberately quiet.
* Left and right take independent brown streams under a shared envelope: one
  event, two ears, decorrelated by the air path.

Rolls arrive every 25-90 s, but the *first* one lands 5-15 s after start so a
listener who taps "Thunderstorm" hears thunder rather than wondering whether
they picked the wrong sound (and so the 20 s offline render contains one).
Peak amplitude is capped at roughly 2x the bed's RMS — the spec's +6 dB ceiling.
Measured: a roll lifts the sub-250 Hz band about 16 dB and the full-band level
about 2-5 dB. This is a sleep app; a roll must never be a jump scare.

Knobs (`ThunderPreset`): intervals, first-roll window, duration, cutoff range,
attack range, sub-roll count, crack probability, `peakAmplitude`, `bedTrim`.

## Ocean

The whole sound is one envelope. A wave lasts 9-15 s (redrawn per wave, so no
predictable rhythm ever establishes), rises over 2/5 of that and drains over
3/5 — real surf breaks quickly and drains slowly, and that asymmetry is what
stops the swell sounding like a tremolo pedal. The envelope never reaches zero
(floor 0.07): the sea does not go silent, and a true zero pumps.

Three layers follow it:

* **Body** — brown (high-passed at 40 Hz) plus 40 % pink for mid presence,
  through a one-pole low-pass whose cutoff sweeps 600 Hz -> 4 kHz with the
  envelope. Loud water is *brighter*, not merely louder; a fixed-timbre swell
  is the giveaway of an amplitude-modulated noise bed.
* **Foam** — white through a 1-3 kHz band-pass, driven by the envelope delayed
  0.4 s (a 19 200-sample ring buffer) and *squared*. Squaring narrows the hiss
  to the top of the wave so it reads as the crest breaking; the delay is what
  makes it a break rather than a swell that gets louder.
* **Wash** — a quiet constant low band: the rest of the beach, which does not
  stop between waves.

Measured: 17.2 dB of envelope depth and a mean swell period of 11.8 s.

Knobs (`OceanPreset`): period range, attack fraction, envelope floor, foam
delay/level/band, wash level, body low-pass sweep range, pink content.

## Wind

Wind has no sound of its own; what we hear is air exciting resonances in what
it passes. So: white noise through band-passes whose centres wander. A low
broad **howl** (250-700 Hz, Q 4 — a large opening) and a quiet high **whistle**
(1.2-2.5 kHz, Q 6, -12 dB — an edge), each on its own slow random walk because
they are different objects. Both are multiplied by a shared gust envelope: a
continuous 0.09 Hz wander with occasional 3-8 s raised-cosine swells dropped on
top by a Poisson clock. The two time scales together are what makes wind sound
alive — the wander alone is too even, the swells alone too periodic. The whole
voice pans slowly by +/-0.3, because wind moves.

Filter centres are retuned at control rate (every 64 samples, 1.3 ms) rather
than per sample: recomputing a biquad's cosines 48 000 times a second would
cost more than the rest of the generator, and the centre moves by a fraction of
a hertz in that time. Measured dominant peak: ~310-580 Hz depending on where
the walk is.

Knobs (`WindPreset`): both bands' ranges and Qs, walk rates, whistle level,
gust depth and rate, swell interval/length, pan drift.

## Campfire

* **Rumble** — brown noise under 120 Hz (high-passed at 55 Hz) with a slow
  flutter: the convection column, and what makes a fire feel *near*.
* **Hiss** — white through 3-7 kHz with a fast random amplitude flutter around
  8 Hz: steam escaping the wood. The flutter is filtered noise rather than an
  LFO, because a fixed rate reads as tremolo.
* **Crackles** — a Poisson stream, 4-12 per second, each a 3-25 ms burst
  through a *resonant* band-pass (900 Hz-5 kHz, Q 5-14). The resonance is the
  whole point: a crackle is a small cavity failing and the cavity has a pitch;
  a plain low-passed tick reads as static. One in fifteen is a "pop" at 3.2x
  the level, which is the single detail that makes people call it a real fire.
  The crackle rate itself drifts between 4 and 12/s over minutes, so the fire
  flares and settles.

The rumble/crackle balance was set from octave-band measurements, not by ear
alone: the first version had the crackles 20 dB under the sub-60 Hz rumble,
which on a phone speaker is a fire you cannot hear. Measured rate: 6.7
crackles/s.

Knobs (`CampfirePreset`): crackle rate range, duration/pitch/Q ranges, crackle
and pop levels, pop probability, rumble cutoff/level/flutter, hiss band and
level.

## Stream

Running water is bubbles: each is a tiny Helmholtz resonator whose pitch is its
size, and a brook is thousands forming and collapsing. Synthesising individual
bubbles is possible but expensive; the cheap equivalent that fools the ear is
six narrow band-passes (Q 6-12) on white noise, spread *geometrically* between
400 Hz and 5 kHz, each **wobbling** in amplitude at 6-14 Hz. The wobble is the
trick — static band-passed noise is a vowel, wobbling band-passed noise is
water. Each resonator also drifts +/-8 % in centre frequency at control rate, and
a broadband wash under 2 kHz sits 10 dB down.

Each resonator has independent noise *and filters per channel*: feeding one
mono resonator to both ears through pan gains made the bank 0.91-correlated and
collapsed the brook into the middle of the listener's head. Pans alternate
outward from centre rather than sweeping low-to-high, because sweeping would
tie pan position to frequency and the bank's downward tilt would then leave the
left side permanently louder. Measured L/R correlation after the fix: 0.004.

Knobs (`StreamPreset`): resonator count, frequency span, Q range, wobble rate
range and depth, drift fraction, pan spread, wash cutoff and level.

## Crickets

A cricket chirp is a nearly pure tone — the wing's file-and-scraper resonates
at one frequency — amplitude-modulated at the wing-stroke rate. Four
individuals, each with:

* a **carrier** sine at 3.6-5.2 kHz, fixed per individual (its species and body
  size), read from a 4096-point wavetable;
* a **trill** at 28-42 Hz, full depth, as `(1 - cos)/2` so the modulator stays
  non-negative and the chirp pulses instead of inverting phase;
* a **chirp gate**, 80-160 ms on / 150-350 ms off, grouped into phrases of 3-7
  chirps with a 1-4 s pause. Real crickets chirp in phrases, and the pauses are
  what let the ear hear four separate animals rather than one texture;
* its own pan and level (distance).

The gate is smoothed by a 4 ms one-pole: switching a 4 kHz sine on in one sample
is a click, and a night full of clicks is the opposite of restful. Underneath
sits a pink bed 24 dB down, high-passed at 250 Hz — a full-range pink bed puts
most of its energy below 200 Hz, where a summer field has nothing.

Calibrated to -26 dBFS. Measured dominant peak 4934 Hz; chirp duty cycle 0.34.
L/R correlation is 0.84, higher than the other sounds, and correctly so: four
crickets are four point sources.

Knobs (`CricketsPreset`): individual count, carrier/trill ranges, chirp on/off
and phrase lengths, pause range, bed level and high-pass.

## Fan

Pink noise, a broad +4 dB resonance at 400 Hz (the housing), a 1.8 kHz one-pole
low-pass (blade noise has no top end), a 4 % amplitude modulation at the 28 Hz
blade rate, and a 110 Hz motor hum 30 dB down. The blade AM is the only part
that has to be exact: deeper and it becomes a helicopter, absent and the sound
is indistinguishable from filtered pink noise. 4 % at 28 Hz sits right at the
edge of conscious perception, which is where a machine bed belongs — the brain
classifies it as a real object and then ignores it. The hum is the one mono
element, which is realistic (one motor) and far too quiet to collapse the image.

Knobs (`FanPreset`): low-pass, resonance frequency/Q/gain, blade rate, AM depth,
hum frequency and level.

## Airplane cabin

Engine and boundary-layer noise conducted through the fuselage — brown noise
low-passed at 900 Hz with a +6 dB peak at 180 Hz (Q 1.2) where the tube
resonates — plus the air-conditioning outlet, white through 2-4 kHz at -18 dB.
Over the top, a 0.02 Hz +/-1 dB drift. That drift matters more than it looks: a
perfectly steady bed is the one thing that reliably reads as synthetic over a
long night, and a 50 s breathing cycle is below the threshold at which anyone
notices modulation but above the threshold at which the sound feels dead.

Knobs (`AirplanePreset`): low-pass, peak frequency/Q/gain, vent band and level,
drift rate and depth.

## Listening by proxy

```
NOISE_RENDER_DIR=/some/dir ./gradlew :core:audio:testDebugUnitTest \
    --tests '*RenderSamplesTest*'
```

writes 20 s of every sound (plus `mix_rain_brown.wav`) as 16-bit stereo WAV,
rendered **through `MixRenderer`** so the slider mapping and soft clipper are
included. Without the variable the test is skipped.

Three views are worth plotting from those files, and each caught a real bug in
this engine:

* a **spectrogram** on a log frequency axis — showed rain's drops, campfire's
  crackles, crickets' trill structure, and the ocean's crest brightening;
* a **short-window RMS envelope** — showed thunder rolls, wind gusts and cricket
  phrases, and confirmed the ocean's swell period;
* **octave-band levels** — the one that matters most, because a per-hertz
  spectrogram makes every brown-noise bed look bass-heavy. This is what exposed
  rain's midrange hole and campfire's inaudible crackles; neither was visible
  in the spectrogram and neither would have failed an RMS test.
