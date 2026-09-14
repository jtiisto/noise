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

One generator, two presets. Four layers:

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
3. **Close drops** — a second, much sparser Poisson stream (4.5/s for Rain,
   11/s for Downpour) at roughly eight times the sheet drops' amplitude, each a
   20-50 ms burst through a *resonant* band-pass (1.5-4 kHz, Q 8-15) and
   individually panned. These are the drops landing within a couple of metres
   of the listener — on the window, on the sill, on a leaf overhead. Without
   them the shower is a texture with no foreground and reads as "a rain
   recording" rather than "rain outside the window"; the ear needs
   individually resolvable events to place itself in a scene. The resonance
   rather than a plain low-pass is what makes each one a *tap on a surface*
   instead of a louder hiss tick.
4. **The body** — brown noise low-passed at 700 Hz and high-passed at 45 Hz:
   rain on ground and roofs a street away. Without it the sound has no depth
   and sits inside the listener's head; below 45 Hz it is pure wasted
   excursion.

Knobs (`RainPreset`): `dropsPerSecond` and `closeDropsPerSecond`, sheet
corners, per-layer levels, both drop streams' duration/colour/Q ranges, gust
rate and depth, voice counts, `outputGain`.

`LIGHT` is 60 drops/s over a 1.2-6 kHz sheet; `DOWNPOUR` is 200 drops/s over a
0.8-8 kHz sheet with twice the low body and quieter individual drops — past
roughly 200/s the ear stops resolving drops and hears a sheet, which is exactly
what heavy rain is, so the extra rate buys density rather than audible ticks.
Measured: 61.2 and 194.9 sheet drops/s against presets of 60 and 200; 5.0
transients per second above 0.34 full scale in the Rain render against a
close-drop preset of 4.5/s, so the close layer lands and is individually
resolvable above a sheet that peaks near 0.25. Peak 0.72, inside the 0.8
ceiling.

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
  first 60 % of a 4-9 s event, each decaying to -40 dB over 70 % of the event
  length, summed and clamped to 1. The overlap is what gives a roll its
  re-swelling rather than a single decaying thud, and the long per-bump decay
  is what makes it *roll*: at the 32 % this started with, the whole thing was
  over in two and a half seconds, which reads as a thump.
* The low-pass sweeps *down* across the event, ending at 45 % of where it
  started. Thunder darkens as it decays, because the later arrivals have
  travelled further through air and off more surfaces and air absorption is
  strongly frequency-dependent. A roll whose timbre is constant reads as a
  filtered noise burst rather than as distance.
* One roll in four gets a short band-passed "crack" at onset (1.2-2.2 kHz,
  9 dB under the roll's own peak): the direct path arriving before the smeared
  reflections. It is deliberately quiet.
* Left and right take independent brown streams under a shared envelope: one
  event, two ears, decorrelated by the air path.

Rolls arrive every 25-90 s, but the *first* one lands 5-15 s after start so a
listener who taps "Thunderstorm" hears thunder rather than wondering whether
they picked the wrong sound (and so the 20 s offline render contains one).
Peak amplitude is capped at roughly 2x the bed's RMS — the spec's +6 dB
ceiling, which the test suite now enforces at 6.5 dB rather than the 8 dB it
originally allowed. Measured: a roll lifts the sub-250 Hz band by 15.4 dB and
stays above the bed for about 4.5 s (3.2 s of that more than 3 dB up), while
the full-band 250 ms level rises 5.6 dB. This is a sleep app; a roll must never
be a jump scare.

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
  through a *second-order* low-pass whose cutoff sweeps 600 Hz -> 4 kHz with
  the envelope. Loud water is *brighter*, not merely louder; a fixed-timbre
  swell is the giveaway of an amplitude-modulated noise bed. Second order
  rather than one pole because 6 dB/oct from 4 kHz still leaves the pink
  component clearly audible at 15 kHz, and crests came out hissy above the
  1-3 kHz band the foam layer is supposed to own.
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

A third voice carries the **buffet**: brown noise between 25 and 90 Hz, gated
by the gust envelope *squared* and capped, because brown noise already has a
crest factor near 4 and an uncapped square of a 1.8 gust would reach full scale
on its own. This is the pressure fluctuation of moving air against whatever the
listener is inside, and it is what gives a gust weight rather than just more
hiss. It is added *after* the pan: below about 100 Hz the ear cannot localise
anyway, and panning it would only unbalance the channels as the image drifts.
Measured: the 30-80 Hz band lifts about 12 dB during a swell and sits near the
noise floor between gusts.

Filter centres are retuned at control rate (every 64 samples, 1.3 ms) rather
than per sample: recomputing a biquad's cosines 48 000 times a second would
cost more than the rest of the generator, and the centre moves by a fraction of
a hertz in that time. Measured dominant peak: ~310-580 Hz depending on where
the walk is.

Knobs (`WindPreset`): both bands' ranges and Qs, walk rates, whistle level,
gust depth and rate, swell interval/length, pan drift, buffet level and band.

## Campfire

The original crackle was a short noise burst through a single *resonant*
band-pass sharing rain's soft difference-of-exponentials envelope (attack/decay
ratio 8, ~3.4 ms rise). Measured against real recordings it was the wrong sound
in three ways at once — soft (attack ~2.5-3.4 ms where a real crack is ~0.1 ms),
dull and pitched (spectral flatness ~0.02, centroid ~1.4 kHz — a narrow ring),
and uniform (all cracks alike) — which is exactly why reviewers heard it as
artificial and its sharper cracks as *rain*. A real fire's cracks are the
opposite: **impulsive** (a near-instant broadband click), **bright/broadband**
(energy well up past 4 kHz, not a pitched ring), and **varied** (low woody pops
through bright snaps), arriving in irregular flurries rather than an even stream.
So the crackle was rebuilt as three event types over the (kept) rumble and hiss
beds:

* **Rumble** — brown noise under 120 Hz (high-passed at 55 Hz) with a slow
  flutter: the convection column, and what makes a fire feel *near*. It is now a
  supporting bed, well down from its old level (a bright fire is only ~8-15 %
  sub-120 Hz), because a loud rumble both dulls the sound and — being high-
  amplitude low-frequency — swamps the cracks.
* **Hiss** — white through 2.5-9 kHz with a fast random amplitude flutter around
  8 Hz: the continuous steam sizzle. Filtered noise, not an LFO, because a fixed
  rate reads as tremolo.
* **Bright snaps** (the majority) — a broadband white burst with a *near-instant*
  attack (~0.1-0.35 ms 10-90 % rise) and a short decay (2-9 ms), gently
  high-passed (~1.1 kHz) and low-passed high (3-7 kHz). No resonance: a real
  crack is an impulsive click, so running it through a narrow band-pass is what
  made the old one read as static. The high-pass only crisps and lightly colours
  it — it stays a broad band over an octave wide, not a ring.
* **Mid crackles** — dimmer, a touch longer, through a lower band (~0.6-3.5 kHz).
* **Low woody pops** (occasional) — the body that says "logs": a short burst
  through a low *resonant* band (150-480 Hz, Q 1.5-3.5) with a slightly longer
  decay. This replaces the old "just louder" pop — a loud crack that is also
  *low and woody*, not merely a bright crack turned up.
* **Flurries** — cracks are not an even Poisson stream. Each Poisson trigger is a
  flurry *head* that spawns 0-3 rapid follow-ons (a capped geometric tail,
  ~10-55 ms apart), so cracks cluster the way a real fire's do. The trigger clock
  runs at the target rate divided by the mean flurry size, so the *audible*
  density still lands in the preset's crackles-per-second range, which itself
  drifts over minutes so the fire flares and settles.

The near-instant attack needed a new voice: `EventVoicePool` gained an
attack-parameterised `startVoice` (the fast term's time constant set from an
explicit attack rather than the fixed decay/8), so a voice can snap open in a
fraction of a millisecond and still decay over several. It stays a smooth
exponential rise from zero (no DC-step click) and costs one `pow` per *spawn*,
never per sample. The broadband snap/mid path reuses `NoiseBurstVoicePool` (the
raindrop's white → one-pole low-pass, now with an optional gentle one-pole
high-pass); the woody pop reuses `ResonantBurstVoicePool`. Both stay
allocation-free (fixed pools, struct-of-arrays) and deterministic (seeded), and
events are mono and panned like rain's drops (L/R correlation ~0.46, inside the
0.95 the spec allows event-dominated sounds).

**Loudness and peaks.** A bright crackling fire is inherently very peaky — sparse
loud cracks over a quiet floor, a crest factor near 27 dB — while the engine
calibrates every sound to -20 dBFS RMS with the peak under the 0.9 three-layer
ceiling (≈19 dB crest). That gap cannot be closed with a louder bed (a bed loud
enough to raise the RMS floor swamps the cracks and dulls the sound), so the
generator keeps the beds low and applies a gentle soft limiter (tanh above a
0.62 knee, asymptoting to a 0.86 ceiling) that catches only the loudest cracks —
enough to hold the peak under 0.9 without squashing the ordinary cracks' sharp
onset. Level variety per crack is kept modest (a flat spread, not the drops'
squared-uniform) precisely so a heavy tail of rare-loud snaps does not inflate
the crest the limiter then has to remove. `outputGain` is the measured
calibration constant (currently 0.72) as for every other generator.

**Tuning method.** The crackle was tuned by rendering the offline WAV and
comparing its per-crack transient metrics — attack sharpness, spectral flatness,
spectral centroid and their spread, and the fraction of energy above 4 kHz —
against real campfire recordings (a bright Commons fire as the primary target
and a low woody CC0 fire), with `research/compare_fire.py`. Measured (final,
through `MixRenderer`) against the bright reference: attack p10 **0.27 ms** (real
~0.1, old ~2.5), flatness median **0.43** (real ~0.13-0.28, old ~0.02), centroid
median **5.2 kHz** (real ~3.9-4.8, old ~1.4 kHz), energy >4 kHz **0.45** (real
~0.42, old ~0.15), centroid spread **1.1-7.1 kHz** (wide, like the real
0.3-7.8 kHz; old was a narrow 0.9-2.4 kHz), and ~15 % of the energy below 120 Hz
(bright, like the reference; the old design and the woody CC0 fire sit near
70 %). `NatureGeneratorTest` asserts the crack *design* on the un-limited shape
(sharp p10 and median attack, broadband flatness far above the old ~0.03, high
and wide centroid), and `GeneratorCalibrationTest` asserts the -20 dBFS level and
the 0.9 peak ceiling on the calibrated output.

Knobs (`CampfirePreset`): crackle rate range and flurry probability/spacing; per
type (snap / mid / woody) the attack, decay, band and level ranges; woody-pop
centre and Q; rumble cutoff/level/flutter; hiss band and level; soft-limiter
knee/ceiling; `outputGain`.

## Stream

Running water is bubbles: each is a tiny Helmholtz resonator whose pitch is set
by its radius, it rings for a few tens of milliseconds, and its pitch *rises*
as it ascends and shrinks under falling pressure. A brook is thousands of them
per second across a range of sizes.

The first version of this generator drove six band-passes with **continuous**
white noise and wobbled their amplitude. That is the standard cheap
approximation, and it is wrong in a way that is obvious the moment you plot it:
six perfectly straight horizontal lines across a twenty-second spectrogram. The
ear hears that as a filtered drone — a vowel — because water has no sustained
partials at all. What it has is *onsets*. Neither the +/-8 % drift nor the
6-14 Hz amplitude wobble was enough to disguise that; you cannot wobble a
sustained tone into an event.

So the excitation is impulsive. Six **size classes**, spread geometrically
between 400 Hz and 5 kHz, each with:

* its own **Poisson clock** at 30-70 bubbles/s and its own
  difference-of-exponentials envelope, whose state is *added* to on each
  trigger so overlapping bubbles sum correctly. The result is a continuously
  fluctuating excitation made of distinct attacks rather than a steady level;
* a **sweep** kicked to 1 by every trigger, decaying with that class's own time
  constant, which pulls the resonator's centre down 35 % and lets it climb back
  — the rising chirp of a bubble;
* **Q 3-5**, not 6-12. A high-Q resonator rings long enough to become a tone; a
  broad one just colours the burst, which is what a bubble does;
* a resting pitch wandering **+/-20 % at 0.3-1 Hz**, fast and wide enough that
  the bands visibly move rather than sitting still;
* an attack 1/5 of the decay rather than the 1/8 the drop and crackle pools
  use: 8:1 gave a spikier onset and a crest factor that ate the mix's headroom,
  and a bubble is a resonance being filled, not a click.

Two normalisations keep rate, duration, Q and centre shaping *texture* and not
loudness: the band-pass's noise gain, and the mean square of a Poisson train of
these pulses (proportional to rate x tau).

Under it all, a quiet broadband wash below 2 kHz — the sheet of water that is
not bubbling.

Each resonator has independent noise *and filters per channel* under a shared
envelope (one bubble, two ears): feeding one mono resonator to both ears
through pan gains made the bank 0.91-correlated and collapsed the brook into
the middle of the listener's head. Pans alternate outward from centre rather
than sweeping low-to-high, because sweeping would tie pan position to frequency
and the bank's downward tilt would then leave the left side permanently louder.

Measured: 312 bubbles/s across the six classes; L/R correlation 0.002; and the
5 ms envelope's p95/p50 is **5.33 dB against 2.49 dB** for the same synth with
its clocks driven fast enough to make the excitation continuous — that control
*is* the old design, so the comparison isolates exactly what changed and needs
no magic absolute threshold. The spectrogram now shows short bright blobs
scattered across 400 Hz-5 kHz instead of lines. It is the peakiest sound in the
catalog (crest factor 18.5 dB), but only about 10 samples per million exceed
0.7 and the 99.99th percentile is 0.56, so the clipper never has real work to
do.

Knobs (`StreamPreset`): resonator count, frequency span, Q range, bubble rate
and duration ranges, sweep depth, drift fraction and rate range, pan spread,
wash cutoff and level.

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
