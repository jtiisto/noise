# UI spec (`app`)

## Design language — "night sky that listens"
- Dark-only. Base colours: near-black indigo `#0B0F1A` → deep navy `#141A2E`.
  Foreground: soft off-white `#ECEBF5` at 92/64/40 % alpha tiers.
- The whole background is a vertical gradient with two large radial "aurora"
  glows (top-right and bottom-left) whose colours are the accent hues of the
  sounds currently in the mix: the first layer owns the top glow, the last
  owns the bottom, and their circular mean is the accent (default = calm
  blue/violet when the mix is empty; a single layer gets its second glow 34°
  around the wheel so the background keeps depth). Colour changes animate over
  900 ms. Glows are plain `Brush.radialGradient` fills — no blur. Each ramp
  ends at its own colour with zero alpha, never `Color.Transparent`, which
  would drag a grey halo through the middle.
- The animated palette is exposed as a `State<MixPalette>` and read inside
  `drawWithCache`/`drawBehind` lambdas, so a colour transition invalidates
  drawing without recomposing the screen.
- Every sound has an accent hue (in `SoundId.hue`) and an icon from
  material-icons-extended (mapping lives in `SoundVisuals`).
- Accents are built in `ui/theme/Accent.kt`, all pure functions:
  `accentColor(hue) = HSL(hue, 0.74, 0.68)`; `accentFor(id)` adds one
  exception — Grey noise's `hue = 0` is pure red, which misdescribes the one
  sound that is meant to be colourless, so it is painted as a cool
  near-neutral instead. A mix's accent is the **circular mean** of its hues
  (`blendHues`), skipping neutrals: averaging the colours in RGB turns three
  spread-out hues grey, while a vector mean stays a colour. Opposing hues
  have no mean, and the lead layer's accent stands in.
- Typography: system sans; hero numbers/titles in Medium weight with tight
  letter-spacing; labels in 12–13 sp with 0.08 em tracking, uppercase for
  section headers. Generous whitespace, 20 dp screen margins, 16 dp card
  radius, 999 dp pills.
- Motion budget: one breathing animation on the play orb (scale 1.0→1.05 and
  glow alpha, 4 s ease-in-out loop, only while playing), tile selection scale
  spring, animated colour/size for state changes, and the home critter's slow
  idle breathe/bob plus small per-animal secondary motions (see **Critter**).
  The critter is the only continuous animation that also runs while paused, and
  it is deliberately gentle; nothing else animates continuously.

## Screens
### Home (single screen, vertical scroll)
1. **Header** — "Hush" wordmark (small caps, tracked), right side: timer
   pill, settings icon. The pill always states the current mode rather than
   naming a screen, so "no timer" reads as a deliberate choice and not an
   absence: a crescent moon plus `27:31`, accent-tinted, while a timer runs;
   an infinity glyph plus "No timer", quiet, while none does. Tap → timer
   sheet either way.
2. **Orb** — centred 168 dp circular play/pause control with layered rings
   (three concentric translucent circles tinted by the mix hue; outer ring
   pulses while playing). It is drawn inside a 200 dp box so the halo behind
   the rings can bloom without being clipped; the 168 dp ring is the visible
   edge of the control. Below it the mix title (`Mix.title()` — e.g.
   "Rain + Brown noise", or "Choose a sound" when empty) and a status line:
   "Playing · sleep timer 27 min" / "Fading out · 0:32" / "Playing quietly ·
   another app is speaking" (ducked) / "Playing · until cancelled" (playing
   with no timer) / "Paused" / "Layer up to three sounds" when the mix is
   empty. The minutes are floored so they agree with the countdown pill
   beside them. Which of the six wins when several are true at once is a pure
   function (`ui/home/playbackStatus`), unit tested; the composable only
   turns its answer into words.
3. **Mix card** (only when the mix is non-empty) — one row per layer, in two
   lines: icon, name and percentage on the first, a full-width accent-tinted
   volume slider on the second with the remove × at its right. (The × shares
   the slider's line rather than the label's because both want a 48 dp target,
   and stacking two of them would make a three-layer card eat half the
   screen.) A "Clear" text button sits opposite the card's header. The card
   animates its height as layers come and go (`animateContentSize`), and
   appears/disappears with `AnimatedVisibility`.
4. **Scenes** — horizontal row of pill chips ("Stormy night", "Cabin",
   "Seaside", "Deep focus", "Summer night", "Long haul"); tap replaces the
   mix. The active scene (mix equality) is highlighted.
5. **Catalog** — three sections (NOISE / NATURE / AMBIENCE) of 3-column
   tiles, 104 dp tall: a 42 dp tinted icon circle, the name over two reserved
   lines (so every row's icons and labels line up whether or not a name
   wraps), and for selected tiles an accent border, a tinted fill and three
   volume dots. Selection settles with a spring on the tile's scale. Tap
   toggles (max 3 → snackbar "Up to three sounds at once"). Long-press opens
   a small bottom sheet with the sound's blurb and a volume slider; when the
   sound is not in the mix that sheet offers "Add to mix" instead, disabled
   with a one-line reason when the mix is full.
6. **Bottom bar** — master volume slider with speaker icon and a percentage
   readout, always visible and pinned below the scroll. It sits on a vertical
   scrim that fades from transparent into the night ground so the catalog can
   scroll under it and stay legible (there is no blur available).

### Critter (Home overlay)
A small, cute animal keeps the orb company — a personal, delightful touch, not
a control.
- **Placement.** It is a pure *overlay* inside the orb's existing 200 dp box
  (`HushSize.orb`), aligned to the bottom-centre so it "sits" at the base of
  the orb, in the gap that was already there between the rings and the mix
  title. **No layout changes:** the orb box keeps its size, so the header, orb,
  mix title, status line, mix card, scenes, catalog and bottom bar do not move.
  The critter never covers the play/pause glyph (centred, well above it), the
  mix title, the timer pill or any control, and it stays clear on both the
  320 dp and 411 dp widths because it is anchored to the centred orb, not the
  screen edges. It is drawn ~54 dp and carries no `contentDescription`, so
  TalkBack skips it.
- **Art.** Compose vector art only (Canvas primitives — circles, ovals, arcs,
  a reused `Path` — never emoji, which layoutlib cannot render and which would
  clash with the line-icon aesthetic). Each animal is a couple of soft fills
  that read on the night ground, rounded and big-eyed; some tint with or
  complement the mix accent (`accentFor`).
- **Mapping** (`critterFor(mix)`, pure and unit-tested). The lead is the
  **first** sound in mix order whose category is `NATURE`:
  Rain / Downpour / Thunderstorm → **frog**, Ocean → **whale** (with a spout),
  Wind → **bird**, Campfire → **fox** (curled, cosy), Stream → **duck**,
  Crickets → **firefly** (a soft glowing blink). A mix with no nature sound —
  only noise, only ambience, or empty — gets the default **sleeping cat**,
  curled up with a tiny floating "z", which suits "Hush" and the sleep theme.
- **Animation.** **One** `rememberInfiniteTransition` drives **two** looping
  values, both read inside the draw lambda so an animating critter invalidates
  drawing without recomposing (the palette is likewise read only there):
  - **breathe** — a 0..1 triangle (2.6 s while playing, 3.8 s while paused,
    ease-in-out, reversing) that becomes the whole-body bob-and-breathe and the
    firefly's belly glow.
  - **clock** — a 0..1 sawtooth (6 s playing, 9 s paused, linear, restarting;
    so it drifts one way and repeats rather than bouncing). Every per-animal
    secondary motion is *derived* from it with pure math — no coroutine, timer
    or `delay`; a blink is just a smooth Hann-window pulse of the clock
    (`pulse(clock, center, width)`, unit-tested), so it eases in and out and
    never strobes.
- **Per-animal secondary motion** (on top of the shared breathe/bob):
  - **Cat** — three accent-tinted "z"s that rise up-and-right, grow and fade,
    staggered a third of the loop apart, so they read as a drifting sleep trail.
  - **Frog** — an occasional slow blink (eyes ease shut into happy arcs, never a
    gap) plus a soft throat pulse on the belly.
  - **Whale** — a steady little fountain with a droplet cluster that rises and
    fades on the loop; gentle bob.
  - **Bird** — an occasional blink and a wing that flutters a few degrees.
  - **Fox** — stays curled and asleep (closed eyes), with an occasional quick
    ear flick.
  - **Duck** — a gentle head nod (dip and tilt) and an occasional blink.
  - **Firefly** — the slow belly-glow blink, with the two wings shimmering
    softly out of phase.
- The two blinking-eye critters share one `blinkingEye` helper: a round shiny
  bead that squashes flat and fades to a happy closed curve as it blinks.
- Secondary motion is livelier while playing and calmer while paused — the
  clock simply runs slower when paused (longer period), and the continuous
  motions (throat, wing flutter, head nod) scale down too.
- When the lead nature sound changes the animal swaps with a ~360 ms
  fade-and-scale (`AnimatedContent`). **Cheap by construction:** one transition
  (never more), no timers or coroutines, no per-frame allocation that grows
  (the single `Path` is reused; `Offset`/`Size`/`Color` are value classes), no
  blur, no heavy Canvas work. **No layout change:** the critter is drawn at the
  same ~54 dp, in the same bottom-centre overlay of the orb's existing box.

#### Critter scenes (branch: `critter-scenes`)
A parallel, bolder art direction that **replaces** the ~54 dp figure above with a
larger illustrated *scene* per sound (`ui/critterscenes/CritterScene.kt`). Owner-
approved and wired into the real Home on this branch only; `main` still ships the
small critter. It reuses the same `CritterKind` + `critterFor(mix)` mapping and
the same `pulse()` motion helper, so the sound→animal mapping is unchanged.
- **Placement.** Still a purely decorative overlay inside the orb's 200 dp box,
  but composed **right of centre at the orb's base**, allowed to overlap the
  play/pause: `Modifier.align(BottomCenter).offset(x = 52.dp, y = 18.dp)`, scene
  `size = 138.dp`. **No non-critter layout changes.** The scene is a bare
  `Canvas` **sibling drawn after (above) the orb** with **no** pointer modifier
  (`clickable`/`pointerInput`/`toggleable`) anywhere, so Compose routes touches
  straight through it — the whole orb/play-pause stays tappable under the overlap.
  It carries no `contentDescription`, so TalkBack skips it. Clears the mix title,
  status line and timer pill on both 320 dp and 411 dp.
- **Trimmed to float.** Every scene floats free over the aurora — no opaque
  panels or water blocks: cat = cushion + free crescent moon + stars + drifting
  z's (window panel removed); duck = the *bottoms-up* dabble (variant 1, the
  approved default) with ripple rings + a reed/cattail (blue water block removed);
  whale = light translucent wave *curves* + spout + moon (filled sea removed);
  fox = campfire + log + embers + warm glow over a soft shadow (opaque ground
  removed); frog = leaf umbrella (variant 0, default) + rain, already floating;
  bird = branch + blowing leaves + gust lines; firefly = grass + blossom + moon +
  glows. Approved variant defaults: frog umbrella (0), duck bottoms-up (1).
- **Animation.** Same budget as the small critter — **one**
  `rememberInfiniteTransition` driving **two** looping floats (breathe, a
  reversing 0..1 triangle 3.2 s playing / 4.6 s paused; clock, a restarting 0..1
  sawtooth 7 s / 10 s), both read only inside the draw lambda (palette too), so
  nothing recomposes per frame. Secondary motion is derived with pure math:
  blinks/twitches/twinkles are Hann-window `pulse()`s of the clock; rain streaks,
  embers, ripple rings and blowing leaves advance with the clock and wrap; waves
  and gust lines drift with it; the whale spout puffs and fades on `sin(π·clock)`.
  Livelier while playing, calmer while paused (a `live` factor + longer periods).
  Cheap by construction: no coroutines/timers, **Paths are reused** from a tiny
  fixed pool, colours/offsets are value classes, no blur (soft glows use the same
  per-frame `radialGradient` brush the shipped orb/firefly already use).
- **Screenshots.** The real Home references already cover all seven on-device
  (idle→cat, rain trio→frog, paused Ocean→whale, Campfire+Wind→fox, and
  Wind/Stream/Crickets in `CritterScreenshots`), plus the 320 dp small phone.
  `CritterSceneMockups.SceneMotionFrames` pins key frames via
  `CritterSceneFrame(kind, breathe, clock, …)` — frog eyes-open vs blink, the
  cat z-trail early vs late, the duck ripple small vs large. The
  `ui.critterscenes` package is Kover-excluded (drawing-only art).

### Sleep timer sheet (modal bottom sheet)
The sheet answers one question — "how long?" — and its three kinds of answer
form a single list, bracketed by two full-width rows:
- **"Until cancelled"** (infinity leading icon) heads the list: no timer at
  all. Pre-selected when `settings.prefersUntilCancelled` and no timer is
  running — a running timer always wins the initial selection, since showing
  "Until cancelled" above a live countdown would be a lie.
- Preset chips 15 / 30 / 45 / 60 / 90 / 120 min in a 3 × 2 grid, all labelled
  "N min" so the grid stays scannable (last used pre-selected).
- **"Custom"** closes the list and reveals a slider 5–480 min in 5-min steps
  with a large readout that does spell out hours ("3 h 20 min").
- "Fade out over" segmented: 15s / 30s / 45s / 60s / 2 min — hidden while
  "Until cancelled" is selected, because nothing fades when nothing is going
  to stop. It is still reachable in Settings (same shared value).
- The chosen length lives in the sheet, not the ViewModel: it is a draft that
  only means anything once the primary button is pressed.
- Primary button, by state: "Play until cancelled" / "Keep playing" when
  already playing (the button is then confirming a mode, not starting one) /
  "Update timer" when one is running / "Start timer" otherwise. It calls
  `playUntilCancelled()` or `startTimer(minutes)` accordingly; either also
  starts playback if the mix is non-empty and paused. "Cancel timer" appears
  below it only while a timer runs.
- The subtitle follows the choice: the countdown while a timer runs, "Hush
  keeps playing until you stop it." under "Until cancelled", "Hush fades out
  and stops on its own." otherwise.

### Settings sheet
- Mix with other apps (switch + one-line explanation).
- Fade-out duration (same segmented control as the timer sheet; shared value).
- About: version, "All sounds are synthesized on your phone — nothing is
  recorded or downloaded", open-source licences list (static text).

## Behaviour
- First tap on play (or timer start) on API 33+ requests `POST_NOTIFICATIONS`
  with a short rationale snackbar if denied; playback proceeds regardless.
- Edge-to-edge, transparent system bars, dark icons off. Portrait-locked.
- Accessibility: every control has a content description; sliders expose
  `ProgressBarRangeInfo`, a percentage (or a duration, for the custom timer
  length) as their state description and a `setProgress` action; tiles
  announce "selected"; minimum 48 dp touch targets (chips and icon buttons
  stay visually small via `minimumInteractiveComponentSize`).
- State comes only from `PlaybackController.state`; the UI is a pure
  function of it plus local sheet-visibility state in a `HomeViewModel`.
  Callbacks reach the tree through one stable `HomeActions` interface that
  the ViewModel implements, so passing them down costs no recomposition.
- Volume sliders are hand-rolled (`ui/components/HushSlider`): a 4 dp track
  and a small luminous thumb on a translucent surface, one `Canvas` and three
  primitives, with the accessibility contract wired up explicitly.
- Each modal sheet is split into a `…Sheet` wrapper (the `ModalBottomSheet`)
  and a `…SheetContent` body. A modal sheet renders into its own window,
  which Compose Preview screenshots cannot capture, so the references render
  the body inside `SheetPreviewFrame` — same scrim, ground, corners and
  handle.

## Crash reports
The app is side-loaded, so nothing collects its stack traces. A
`Thread.UncaughtExceptionHandler` installed as the first statement of
`HushApplication.onCreate` (before Koin, because a crash while the graph is
being built is the one worth catching) writes `filesDir/crash/last_crash.txt`
and then hands the crash back to the handler it replaced, so Android's own
dialog and process kill are unchanged. One file, overwritten: only the last
crash matters. The report is plain text — timestamp, thread, versionName and
versionCode, manufacturer/model, Android release and API level, then the full
stack trace with its `Caused by` chain. Formatting is a pure function
(`crash/CrashLog.format`), the file plumbing a `CrashReportStore` over one
directory, so both are unit tested and neither can throw out of the handler.

On the next launch `HomeViewModel` reads the report once and, if there is one,
Home shows a dismissible card between the header and the orb: an outline
warning glyph, "Hush crashed last time", and the actions **Share report** and
**Dismiss**. It uses the mix card's surface, hairline and text buttons rather
than a coloured banner — the app still works, and the night ground has no room
for an alarm. Share opens the system share sheet (`ACTION_SEND`, `text/plain`,
the report in `EXTRA_TEXT`; no `FileProvider`) and hides the card but keeps the
file, so backing out of the share sheet does not lose the trace. Dismiss
deletes it. While a report exists, Settings carries a "Share last crash report"
row under About; once dismissed, the row disappears with it.

## Screenshot tests (`app/src/screenshotTest`)
`@PreviewTest` previews via `FakePlaybackController`, on a 360 × 780 phone
frame unless noted: empty mix (idle), playing 3-layer mix with a running
timer, playing 2-layer mix with no timer (the "No timer" pill and the
"until cancelled" status line), paused 1-layer mix, timer sheet (running),
timer sheet with "Until cancelled" selected, timer sheet with the custom
slider open, settings sheet, catalog long-press sheet in the mix, the crash
notice above a paused single-layer mix, and
— at 320 × 640, to prove the smallest supported screen — the playing home
screen, the long-press sheet for a sound the full mix has no room for, and
one catalog section (the three-column grid with the longest labels in it).
Reference PNGs are committed under `app/src/screenshotTestDebug/reference/`;
`validateDebugScreenshotTest` runs in the pre-push hook.

`CritterScreenshots` adds the critter coverage the states above do not already
carry: a `CritterGallery` of all seven animals, each on the ground tint of its
sound, plus the home screen leading with Wind (bird), Stream (duck) and
Crickets (firefly). The cat, frog, whale and fox already appear in the states
above (empty mix, the rain trio, paused Ocean, and Campfire + Wind), which the
critter changed and which were regenerated. Because a still render captures one
instant, the secondary motion would never show; `CritterMotionFrames` therefore
pins key frames the live critter only passes through — via an internal
`CritterFrame(kind, breathe, clock, …)` that freezes the animation values — a
frog eyes-open and mid-blink, the cat's "z" trail at two drift positions, a
whale spout part-way up and a fox with one ear flicked.
