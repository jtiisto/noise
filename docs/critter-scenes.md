# Critter scenes — a bolder art direction (design exploration)

**Status: EXPLORATION / not shipped.** These are *static* mockups on a parallel
branch for the owner to react to. Nothing here is wired into the live app, and
the shipped critter (`ui/critter/Critter.kt`, `HomeScreen.kt`) is untouched.
Everything is drawn as Compose vector art so it renders in the JVM screenshot
harness — no emoji, no bitmaps, no animation.

## The idea
Today each critter is a tiny ~54 dp figure sitting at the base of the play orb.
This explores a **bolder** version where each sound gets a small illustrated
*scene* — the same established character (cat, frog, whale, fox, bird, duck,
firefly) doing something in a little world that matches its sound. Think
"storybook sticker": a bit more detail than today, still calm and refined for a
sleep app. The palette and character designs match the shipped critters, then
extend them with environments; each scene's glow and a few props pick up the
sound's accent hue (`accentFor`) so it stays coherent on the night ground.

## Where it lives
- `app/src/main/kotlin/dev/jtiisto/noise/ui/critterscenes/CritterScene.kt` — the
  draft `@Composable CritterScene(kind, palette, variant, size)`. It dispatches
  on the existing `CritterKind`, so the scenes map 1:1 onto the sounds the live
  critter already covers. All drawing lives inside the composable's `Canvas`
  lambda (colours, helpers, per-scene art), so the draft needs no unit tests and
  does not touch the coverage gate.
- `app/src/screenshotTest/kotlin/dev/jtiisto/noise/CritterSceneMockups.kt` — the
  `@PreviewTest` gallery. Rendered PNGs land under
  `app/src/screenshotTestDebug/reference/.../CritterSceneMockupsKt/`.

## How to look at them
- `SceneGallery` — all seven scenes together, to judge the family as a whole.
- `SceneCatNook`, `SceneRainFrog`, `SceneStreamDuck`, `SceneOceanWhale`,
  `SceneCampfireFox`, `SceneWindBird`, `SceneCricketsFirefly` — each scene on its
  own at ~276 dp so the detail is visible.
- `SceneAlternates` — the two alternate compositions beside their primaries.
- `SceneNearOrb` — one scene (frog) composed at ~150 dp at the base of a *mock*
  play orb, to judge how a larger scene would sit on the home screen. The orb
  there is a stand-in; the real `HomeScreen` is not modified.

## The scenes

| Sound | Character | Scene |
|---|---|---|
| Rain / Downpour / Thunderstorm | Frog | Sheltering under a big leaf umbrella; rain streaks, a soft lightning glow behind (tinted by the storm accent), a puddle with a ripple. |
| Stream | Duck | Dabbling at the water's edge, head dipped toward a little fish peeking at the surface; ripple rings, reeds/a cattail, a lily-pad bank. ("fishing from a stream") |
| Ocean | Whale | Breaching among gentle layered waves with a fuller spout; a crescent moon and a couple of stars. |
| Campfire | Fox | Curled asleep beside a small crossed-log fire; a warm cosy glow, embers drifting up, a star or two. |
| Wind | Bird | Perched on a slender branch swaying in the gust; leaves and a few faint wind streaks blowing past. |
| Crickets (summer night) | Firefly | Hovering among tall grass and a night blossom under a crescent moon; a couple of extra soft firefly glimmers. |
| Default (noise / ambience / empty mix) | Cat | Curled asleep in a cosy nook — a cushion, a moonlit window with a crescent moon and stars, drifting accent-tinted z's. |

## Alternate compositions offered
- **Frog** — *umbrella* (holds the leaf aloft on a stem, primary) vs *leaf tent*
  (a big leaf leaning over the frog like a tent — softer, less "prop-y").
- **Duck** — *fishing* (serene head-dip toward a fish, primary) vs *bottoms-up*
  (the whimsical full dabble — tail straight up, head under, ripple rings).

Both are selected with the `variant` parameter (`0` = primary). More alternates
are easy to add the same way — e.g. a calmer "whale resting on the surface,
spouting" instead of the breach, or a campfire framed by two logs the fox leans
against — if the owner wants to compare.

## Notes / caveats for whoever reviews these
- **Size.** Scenes are drawn scale-independently, so they render cleanly from
  ~120 dp up to the ~276 dp detail tiles. At the base of a 200 dp orb box, ~150 dp
  reads well (see `SceneNearOrb`) — noticeably bolder than today's 54 dp figure,
  and it does begin to crowd the orb, so if this direction is adopted the orb
  area's spacing would need a real layout pass (deliberately out of scope here).
- **Still frames only.** These carry no motion. If a direction is chosen, the
  animation budget from `specs/ui.md` (one shared transition, secondary motion
  derived from a clock) would need re-thinking for the richer scenes.
- **Open question for the owner:** how much scene vs how much character? The
  current compositions lean fairly "full vignette". A lighter take (character +
  one or two props, less environment) is also viable and would sit more calmly
  on the home screen.
