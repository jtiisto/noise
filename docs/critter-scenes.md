# Critter scenes — a bolder art direction (branch `critter-scenes`)

**Status: APPROVED on this branch, wired + animated.** The owner approved the
direction and placement; the scenes are now trimmed to float, wired into the real
Home screen, and animated. This lives on the `critter-scenes` branch only —
`main` still ships the small ~54 dp critter (`ui/critter/Critter.kt`), which is
left untouched (its own tests stay green). Everything is drawn as Compose vector
art so it renders in the JVM screenshot harness — no emoji, no bitmaps.

## The idea
Today each critter is a tiny ~54 dp figure at the base of the play orb. This is a
**bolder** version: each sound gets a small illustrated *scene* — the same
established character (cat, frog, whale, fox, bird, duck, firefly) doing something
in a little world that matches its sound. "Storybook sticker": more detail than
the small figure, still calm and refined for a sleep app. The character designs
and palette match the shipped critters; each scene's glow and a few props pick up
the sound's accent hue (`accentFor`) so it stays coherent on the night ground.

## Where it lives
- `app/src/main/kotlin/dev/tapio/hush/ui/critterscenes/CritterScene.kt` — the
  `@Composable CritterScene(kind, isPlaying, palette, variant, size)` (live) and
  `internal CritterSceneFrame(…)` (phase-pinned, for screenshots). It dispatches
  on the existing `CritterKind`, reuses `critterFor(mix)` from `ui/critter`, and
  reuses that package's unit-tested `pulse()` motion helper. All colours, helpers
  and per-scene art live inside a single drawing-only `DrawScope.drawScene(…)`, so
  the package carries no counted logic; it is Kover-excluded.
- `app/src/main/kotlin/dev/tapio/hush/ui/home/HomeScreen.kt` — the real Home
  now shows `CritterScene` (was `Critter`) as a decorative overlay in the orb box.
- `app/src/screenshotTest/kotlin/dev/tapio/hush/CritterSceneMockups.kt` — the
  isolated gallery/detail/alternates/placement studies plus `SceneMotionFrames`
  (phase-pinned key frames). Rendered PNGs land under
  `app/src/screenshotTestDebug/reference/.../CritterSceneMockupsKt/`. The real
  Home references (`HomeScreenshotsKt`, `CritterScreenshotsKt`) also cover all
  seven scenes in context.

## Placement (owner-approved, 2026-09-13)
- The scene sits **right of centre at the orb's base**, overlapping the play/pause
  a little: inside the orb's existing 200 dp box (`HushSize.orb`),
  `Modifier.align(BottomCenter).offset(x = 52.dp, y = 18.dp)`, scene `size = 138.dp`.
- **No non-critter layout changes** — the header, orb, mix title, status line,
  mix card, scenes, catalog and bottom bar do not move. Clears the title, status
  line and timer pill on both 320 dp and 411 dp.
- **The orb stays fully tappable under the overlap.** The scene is a bare `Canvas`
  drawn as a **sibling after (above) the orb** in the same `Box`, with **no**
  `clickable`/`pointerInput`/`toggleable` modifier anywhere — Compose only routes
  touches to composables that carry a pointer modifier, so touches pass straight
  through the decorative scene to the orb beneath. It has no `contentDescription`,
  so TalkBack skips it.

## The scenes (trimmed to float)
Every scene floats free over the aurora — no opaque panels or filled water/ground
blocks. Approved variant defaults: **frog umbrella (0)**, **duck bottoms-up (1)**.

| Sound | Character | Scene (after trim) |
|---|---|---|
| Rain / Downpour / Thunderstorm | Frog | Under a leaf **umbrella** (variant 0), rain streaks falling, a soft lightning glow, a puddle ripple. Already floated. |
| Stream | Duck | The whimsical **bottoms-up** dabble (variant 1): tail up, head under, feet paddling, expanding **ripple rings**, a reed + cattail. *Filled blue water block removed.* |
| Ocean | Whale | Breaching among **light translucent wave curves**, a fuller spout, crescent moon + stars. *Filled sea block removed.* |
| Campfire | Fox | Curled asleep beside a crossed-log fire, embers drifting up, warm glow, over a **soft shadow**. *Opaque ground rectangle removed.* |
| Wind | Bird | Perched on a swaying branch, leaves + faint gust lines blowing past. Floats. |
| Crickets | Firefly | Among swaying grass + a night blossom under a crescent moon, extra soft glimmers. Floats. |
| Default (noise / ambience / empty) | Cat | Curled asleep on a cushion under a **free crescent moon + a star or two**, drifting accent-tinted z's. *Framed window panel removed.* |

Alternate compositions still exist behind `variant` for comparison (frog: umbrella
vs leaf-tent; duck: fishing-a-fish vs bottoms-up) — see `SceneAlternates`.

## Animation (per-scene, calm & cheap)
Same tight budget as the shipped critter:
- **One** `rememberInfiniteTransition` drives **two** looping floats, both read
  only *inside* the draw lambda (the palette too), so an animating scene
  invalidates drawing without recomposing:
  - **breathe** — a reversing 0..1 triangle (3.2 s playing / 4.6 s paused,
    ease-in-out): the gentle body bob/breathe, throat/flame swell, firefly glow.
  - **clock** — a restarting 0..1 sawtooth (7 s / 10 s, linear): everything that
    drifts one way and repeats.
- **Secondary motion is derived with pure math** — no coroutine, timer or `delay`:
  blinks/twitches/twinkles are Hann-window `pulse(clock, center, width)`s; rain
  streaks, embers, ripple rings and blowing leaves advance with the clock and
  wrap; waves and gust lines drift with it; the whale spout rises and fades on
  `sin(π·clock)`.
- **Per scene:** frog blinks + umbrella sways + rain falls + lightning glimmer +
  throat pulse; duck bobs with ripple rings expanding + reed sway + occasional
  tail wiggle; whale bobs on the swell with the spout puffing + waves drifting;
  fox breathes with embers drifting up + fire flickering; bird flutters a wing +
  blinks + branch sways + leaves blow past; firefly glow pulses and it drifts among
  swaying grass + glimmers; cat breathes with z's drifting up + a slow star twinkle.
- **Livelier while playing, calmer while paused** — a `live` factor scales the
  continuous motions and the clock runs slower when paused.
- **Cheap by construction:** one transition, no timers/coroutines, no per-frame
  allocation that grows — `Path`s are reused from a tiny fixed pool, and
  `Offset`/`Size`/`Color` are value classes. No blur; the soft glows use the same
  per-frame `radialGradient` brush the shipped orb and firefly already use.

## How to look at them
- Real Home in context: `HomeScreenshotsKt/HomeIdleEmptyMix` (cat),
  `HomePlayingThreeLayers` (frog), `HomePausedSingleLayer` (whale),
  `HomePlayingUntilCancelled` (fox), `CritterScreenshotsKt/HomeWindBird` (bird),
  `HomeStreamDuck` (duck), `HomeCricketsFirefly` (firefly), and `HomeSmallPhone`
  (320 dp).
- Isolated / studies: `SceneGallery`, the per-scene `Scene…` tiles,
  `SceneAlternates`, `SceneNearOrb`, `ScenePlacementRightOfCentre`.
- Motion key frames (a still can't show motion): `SceneMotionFrames` pins
  frog eyes-open vs blink, cat z-trail early vs late, duck ripple small vs large.

## Notes for the orchestrator
- **Tap test on device:** the overlap is only decorative — tap anywhere on the
  orb (including under the critter, right of centre) and it must play/pause. This
  is guaranteed structurally (no pointer modifier on the scene), but confirm on
  the emulator.
- **Branch APK:** build from this branch as usual (`:app:assembleDebug` /
  `assembleRelease`); nothing else in the app changed. `HushApplication`, DI,
  playback and the audio engine are untouched.
