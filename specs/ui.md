# UI spec (`app`)

## Design language — "night sky that listens"
- Dark-only. Base colours: near-black indigo `#0B0F1A` → deep navy `#141A2E`.
  Foreground: soft off-white `#ECEBF5` at 92/64/40 % alpha tiers.
- The whole background is a vertical gradient with two large radial "aurora"
  glows (top-right and bottom-left) whose colours are the accent hues of the
  sounds currently in the mix (blend up to 3; default = calm blue/violet when
  the mix is empty). Colour changes animate over 900 ms. Glows are plain
  `Brush.radialGradient` fills — no blur.
- Every sound has an accent hue (in `SoundId.hue`) and an icon from
  material-icons-extended (mapping lives in `SoundVisuals`).
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
   pill (shows `⏱ 27:31` counting down when a timer runs, "Timer" otherwise;
   tap → timer sheet), settings icon.
2. **Orb** — centred 168 dp circular play/pause control with layered rings
   (three concentric translucent circles tinted by the mix hue; outer ring
   pulses while playing). Below it the mix title (`Mix.title()` — e.g.
   "Rain + Brown noise", or "Choose a sound" when empty) and a status line
   ("Playing · sleep timer 27 min" / "Paused").
3. **Mix card** (only when the mix is non-empty) — one row per layer: icon,
   name, a slim volume slider (0–100, accent-tinted), remove ×. A "Clear"
   text button at the card's end. Row appear/disappear animates height.
4. **Scenes** — horizontal row of pill chips ("Stormy night", "Cabin",
   "Seaside", "Deep focus", "Summer night", "Long haul"); tap replaces the
   mix. The active scene (mix equality) is highlighted.
5. **Catalog** — three sections (NOISE / NATURE / AMBIENCE) of 3-column
   tiles: icon in a tinted circle, name, and for selected tiles an accent
   border + tiny volume dots. Tap toggles (max 3 → snackbar "Up to three
   sounds at once"). Long-press opens a small bottom sheet with the sound's
   blurb and a volume slider.
6. **Bottom bar** — master volume slider with speaker icon (always visible,
   pinned).

### Sleep timer sheet (modal bottom sheet)
- Preset chips 15 / 30 / 45 / 60 / 90 / 120 min (last used pre-selected);
  "Custom" reveals a slider 5–480 min in 5-min steps with a large readout.
- "Fade out over" segmented: 15 s / 30 s / 45 s / 60 s / 2 min.
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
  percentage; tiles announce "selected"; minimum 48 dp touch targets.
- State comes only from `PlaybackController.state`; the UI is a pure
  function of it plus local sheet-visibility state in a `HomeViewModel`.

## Screenshot tests (`app/src/screenshotTest`)
`@PreviewTest` previews via `FakePlaybackController`: empty mix (idle),
playing 3-layer mix with timer, paused 1-layer mix, timer sheet, settings
sheet, catalog long-press sheet. Reference PNGs are committed under
`app/src/screenshotTestDebug/reference/`; `validateDebugScreenshotTest` runs
in the pre-push hook.
