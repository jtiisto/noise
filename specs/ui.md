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
