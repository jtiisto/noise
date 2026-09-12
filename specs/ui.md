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
  spring, animated colour/size for state changes. Nothing else animates
  continuously.

## Screens
### Home (single screen, vertical scroll)
1. **Header** — "Hush" wordmark (small caps, tracked), right side: timer
   pill (a crescent-moon glyph plus `27:31` counting down when a timer runs,
   "Timer" otherwise; accent-tinted while running; tap → timer sheet),
   settings icon.
2. **Orb** — centred 168 dp circular play/pause control with layered rings
   (three concentric translucent circles tinted by the mix hue; outer ring
   pulses while playing). It is drawn inside a 200 dp box so the halo behind
   the rings can bloom without being clipped; the 168 dp ring is the visible
   edge of the control. Below it the mix title (`Mix.title()` — e.g.
   "Rain + Brown noise", or "Choose a sound" when empty) and a status line:
   "Playing · sleep timer 27 min" / "Fading out · 0:32" / "Playing quietly ·
   another app is speaking" (ducked) / "Playing" / "Paused" / "Layer up to
   three sounds" when the mix is empty. The minutes in the status line are
   floored so they agree with the countdown pill beside them.
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

### Sleep timer sheet (modal bottom sheet)
- Preset chips 15 / 30 / 45 / 60 / 90 / 120 min, all labelled "N min" so the
  grid stays scannable (last used pre-selected); "Custom" reveals a slider
  5–480 min in 5-min steps with a large readout that does spell out hours
  ("3 h 20 min").
- "Fade out over" segmented: 15s / 30s / 45s / 60s / 2 min.
- The chosen length lives in the sheet, not the ViewModel: it is a draft that
  only means anything once "Start timer" is pressed.
- Primary button "Start timer" (or "Update" if running) + "Cancel timer" when
  running. Starting the timer also starts playback if the mix is non-empty and
  paused.

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

## Screenshot tests (`app/src/screenshotTest`)
`@PreviewTest` previews via `FakePlaybackController`, on a 360 × 780 phone
frame unless noted: empty mix (idle), playing 3-layer mix with a running
timer, paused 1-layer mix, timer sheet (running) and timer sheet with the
custom slider open, settings sheet, catalog long-press sheet in the mix, and
— at 320 × 640, to prove the smallest supported screen — the playing home
screen, the long-press sheet for a sound the full mix has no room for, and
one catalog section (the three-column grid with the longest labels in it).
Reference PNGs are committed under `app/src/screenshotTestDebug/reference/`;
`validateDebugScreenshotTest` runs in the pre-push hook.
