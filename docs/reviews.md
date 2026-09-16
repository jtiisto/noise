# Review log

Codex full-repo reviews and how each finding was resolved. Accepted findings
(not fixed) stay listed with the reason.

## 2026-09-15 — Ported Notch engine fixes (E1–E3, shared code)

Notch (`~/dev/native/notch`) was bootstrapped from Hush, so its Codex engine
review (2026-09-15, Notch `docs/reviews.md`) found bugs in code Hush shares.
The three the review marked **(Hush too)** and scoped to the engine core are
ported here, each with the regression test Notch added.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| E1 | High | A completed sleep fade left `armedFadeId`, the snapshot's fade request and `sleepPhase = 0` in place, so every later `start()` (which only flipped `playing`) rendered silence until the process restarted — "sleep timer ends → press play again → silence". | **Fixed** — `MixRenderer.start()` also clears `fadeOut`; the existing cancel branch then ramps the sleep envelope back to full, and a late `cancelFadeOut` after completion fires nothing. `MixRendererTest.startAfterACompletedSleepFade` and `cancelAfterCompletionIsInert`. |
| E2 | High | `AudioTrackEngine` released the lock between the render thread's exit decision and clearing `running`/`thread`, so a `start()` in that window saw "running", returned, and then the thread died — playback requested, nothing playing. | **Fixed** — `thread`/`running` are cleared inside the same `synchronized` block as the exit decision; the `finally` block remains for the error path. Device glue, no JVM test (as in Notch). |
| E3 | Medium | A `stop()` before the first render never set `isFinished` (a `startedOnce` guard), so the sink looped writing silence forever. | **Fixed** — the `startedOnce` guard is removed; the engine reports finished whenever stopped with the start envelope at zero. `MixRendererTest.stopBeforeTheFirstRenderFinishes`. |

## 2026-09-15 — Ported the remaining (Hush too) engine findings (E4, E8, E9, E10)

The follow-up pass on the rest of the Notch engine review. All in the shared
audio engine.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| E4 | Medium | The sleep fade stepped a Float phase per sample (`phase / durationSamples` floored at `MIN_STEP`); a requested 120 s fade finished in 117 s at 48 kHz and 49 s at 192 kHz — silence while the user was still awake. | **Fixed** — `MixRenderer` counts the fade-down in samples (`sleepFrom * sleepRemaining / sleepTotal`, exactly zero on the last sample); the climb back out of a cancelled fade still uses the Float ramp. `SleepFadeDurationTest` at 44.1/48/96/192 kHz and a re-armed fade. |
| E8 | Medium | Teardown called `pause()/flush()` right after the renderer reached zero, discarding the queued tail of the fade. | **Fixed** — one buffer of blocking silence is drained through first (`drainQueuedAudio`), abandoned the moment a `start()` lands. Device glue, no JVM test. |
| E9 | Medium | A partial `AudioTrack.write` and the dead-object rebuild dropped rendered samples, punching a gap into a fade. | **Fixed** — an offset loop writes the whole block; a rebuild re-sends it from the start; a bound on zero-progress writes. Device glue, no JVM test. |
| E10 | Low | `EqualPowerFade`'s 4097-entry table was built lazily in its class initialiser, i.e. on the audio thread at the first render. | **Fixed** — touched in `MixRenderer`'s constructor. (Hush has no `FastSine`.) |

**P4 (SessionGuard) intentionally not ported.** Notch's P4 fenced the engine's
trailing `stop()` to the session that armed the fade, for a fade completing
after the controller had started *matching* — a flow Hush does not have. In
Hush the trailing `stop()` is the `beginFadeOut { onComplete(); stop() }`
callback, which only fires on a genuine completion: E1 makes `start()` clear the
fade request (dropping the completion), E2 fences the thread teardown, and the
controller already ignores a completion whose fade generation no longer matches
(2026-09-12 review #3). So the P4 scenario is already covered; the SessionGuard
machinery would add complexity without a reachable Hush bug.

## 2026-09-15 — Ported the (Hush too) playback findings (P3, P5, P7)

The playback half of the follow-up. All three were present in Hush.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| P3 | High | The headphone-unplug (`BECOMING_NOISY`) receiver was registered only when a focus request was granted, so "Mix with other apps" — which never requests focus — left unplug detection off, and pulling the headphones out blared the speaker. | **Fixed** — a new `AudioFocusGate.setNoisyMonitoring(enabled)`, independent of `request()`/`abandon()`; the controller arms it whenever the engine starts rendering and disarms it on every stop, focus or not. `PlaybackNoisyAndRestoreTest`. |
| P5 | High | Restore requested audio focus before the foreground service existed; from API 31 the system refuses focus to a background app, so a sticky restart never resumed. | **Fixed** — `startPlayback(startServiceFirst = true)` on the restore path promotes the service first, and one retry 1.5 s later covers the service still being promoted (stood down by any user command). Three tests. |
| P7 | Medium (first half) | `COMMAND_RELEASE` was not advertised, so `SimpleBasePlayer.release()` in the service's `onDestroy` was a no-op and the media session leaked. | **Fixed** — the command is advertised. Hush's `handleRelease` already pauses the controller, which is correct on a real destroy (Hush has no service-less playback like Notch's matching), so only the first half applies. |

With this the shared-code subset of the Notch review is fully ported (E1–E3 in
d20c5ed, E4/E8/E9/E10 in b7574ff, P3/P5/P7 here), except P4, deliberately not
ported for the reason above.

## 2026-09-12 — Codex review #1 (first integrated build, commit 96ebc6c)

Read-only review of every Kotlin source, the Gradle files and the manifests
after the audio engine, playback core and UI were integrated. Codex's
verdict: "unusually well-engineered"; five findings, none stylistic.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| 1 | Critical | `Process.setThreadPriority` ran outside the audio thread's try/catch; an OEM that refuses the audio nice level would throw and kill the process. | **Fixed** — wrapped in `runCatching` with a warning log (`AudioTrackEngine.renderLoop`). |
| 2 | High | `restore()` suspends on the DataStore read; a command dispatched before it returns was overwritten by the persisted state, and a timer armed in that window left an orphaned tick job. | **Fixed** — commands are queued until restore has applied the persisted state, then replayed in order; `armTimer` stops any running timer first. Regression test with a suspending fake store. |
| 3 | High | A sleep-fade completion posted from the audio thread could land after the user restarted the timer and cancel the new one. | **Fixed** — fade generation counter; a completion whose generation no longer matches is ignored. Regression test with a late callback. |
| 4 | Medium | `Mix`/`PlaybackState` are plain data classes with a `List`, so every composable taking them was non-skippable and the 1 s timer tick re-executed the whole catalog. | **Fixed** — `compose-stability.conf` marks the cross-module state types stable; the Compose compiler report now shows 33/33 restartable composables skippable. |
| 5 | Medium | Turning *Mix with other apps* off while playing ignored a denied focus request and kept playing without focus. | **Fixed** — a denied request pauses, consistent with `play()`. Test with a denying focus gate. |

Verified-correct notes from the review, kept as regression guards: the
`MixRenderer` exactly-once fade contract, `toggleSound`'s decide-then-dispatch
pattern under `Main.immediate`, the `AudioTrack` buffer fallback, DSP filter
stability for an eight-hour run, and the minimal R8 rules (release build
confirmed with `assembleRelease`, 1.7 MB signed APK).

## 2026-09-12 — Codex review #2 (diff since 96ebc6c, commit e9259ee)

Diff-scoped follow-up covering the review-#1 fixes, the audio polish round
and the Compose stability configuration. All five review-#1 findings were
re-verified as fixed (the cold-start queue traced end to end for deadlock,
reordering and dropped commands; the stability config checked against the
generated compiler report).

| # | Severity | Finding | Resolution |
|---|---|---|---|
| 1 | High | `AudioTrackEngine` cleared `running` *after* the lock-free `releaseTrack()`; a `start()` landing during that JNI window saw "running" and no-oped, leaving the controller playing with no audio thread (a phone call ending immediately after it started). | **Fixed** — the running state is cleared under the lock before the track is released, for all three exit paths. |
| 2 | Medium-High | A `store.load()` failure opened the command gate but the exception escaped a coroutine on the application scope, which has no handler — a process crash at launch on a disk read error. | **Fixed** — `restore()` falls back to defaults and counts the failure; `DataStoreStateStore` swallows and logs `IOException` on both load and save. The cold-start test now asserts nothing escapes. |
| 3 | Medium | Four of the five polish behaviours (rain close drops, thunder darkening, wind buffet, ocean second-order body) had no test that could fail. | **Fixed** — behaviour tests added with preset-knob controls (see `NatureGeneratorTest`). |

## 2026-09-12 — Field crash #1: foreground-service start (fixed in 0.1.1)

The user hit Android's "Hush keeps stopping" on a real phone, then reproduced
it by adding a sound and pressing play. Reproduced on a local API 35 emulator
and captured the trace:

```
android.app.RemoteServiceException$ForegroundServiceDidNotStartInTimeException:
Context.startForegroundService() did not then call Service.startForeground(): HushPlaybackService
```

**Cause.** `play()` starts the service with `startForegroundService()`, which
obliges the service to call `startForeground()` within ~5 s. `HushPlaybackService`
left that to Media3, which only promotes once its player-state listener has run;
on the first play the POST_NOTIFICATIONS dialog delays that past the deadline, so
the OS killed the process. Not Airplane-specific — the first foreground start.
No unit test could catch it (the service is device-only glue) and no emulator
run had happened before shipping.

**Fix.** `HushPlaybackService.onStartCommand` now calls `startForeground()`
immediately with a placeholder notification on Media3's own channel and id
(1001), so the OS contract is met the instant the service starts; Media3
replaces the notification when the session is ready. Verified on the emulator
(debug and release): `isForeground=true foregroundId=1001 type=mediaPlayback`,
zero crashes through play → allow notifications → add Airplane cabin, past the
5 s deadline. A `SampleRateRobustnessTest` was also added (every generator at
22.05–192 kHz) to rule out the device's non-48 kHz output rate, which unit
tests had never exercised.

**Process change (CLAUDE.md):** a headless emulator now runs on the build
server (KVM), so on-device smoke testing is part of the release gate, not
optional. This crash would have been caught before shipping by a single launch.

## 2026-09-12 — Overnight-playback gap: no wake lock (fixed in 0.1.2)

User asked whether the app can run indefinitely overnight. The foreground
service (type mediaPlayback) keeps the *process* alive, but nothing held a
wake lock — `WAKE_LOCK` was declared and never acquired. Our custom AudioTrack
engine and stub `SimpleBasePlayer` give none of ExoPlayer's `setWakeMode`
behaviour, so on deep sleep the render thread can be descheduled and audio
stalls. (The audio server's own `AudioMix` lock only holds while the track is
actively mixing — not a guarantee once our thread misses a refill.)

**Fix.** A `WakeLock` port on the controller, held exactly while the engine
renders: acquired right after `engine.start()` in `startPlayback`, released
after `engine.stop()` in `pauseInternal`, so it spans the sleep-timer fade and
survives a focus-loss/gain cycle. `AndroidWakeLock` wraps a non-reference-counted
`PARTIAL_WAKE_LOCK`. Six unit tests cover every transition (play/pause, focus
loss+gain, timer fade to completion, clear, focus-denied).

Verified on the emulator: `hush:playback` held (uid=dev.tapio.hush) while
playing, released on pause — after discovering that `adb install -r` had been
silently not updating the emulator (stale APK), which masked the fix through
several rounds. Lesson recorded below.

**Tooling lesson.** `adb install -r` can no-op without an error in a combined
command; always `adb uninstall` then `adb install` and assert the installed
base.apk md5 matches the on-disk APK before trusting an on-device result.

## 2026-09-12 — Catalog tiles looked top-heavy (fixed in 0.1.3)

User reported the icon and label weren't centered on the catalog tiles
(screenshot). Horizontal centering measured correct; the real issue was
vertical: the tile was a single centered Column of icon + label + a
6dp spacer + a volume-dots row that was empty (but height-reserved) on
unselected tiles. Centering that whole block pushed the visible icon+label
upward, leaving a large empty gap at the bottom of every tile.

**Fix.** The tile is now a Box: the icon+label are one group centered with
`align(Center)`, and the volume dots are pinned to `BottomCenter` so they no
longer pull the optical centre up. The label sits in a fixed two-line slot
(text centred in it) so icons stay aligned across a row whether a label is one
line ("Rain") or two ("Airplane cabin"). Verified by rendering the catalog at
411 dp (the reporter's phone width) in the screenshot harness; all committed
references regenerated and validated.


## 2026-09-12 — Cute companion critter (0.1.4)

A small, gently-animated animal now sits at the base of the play orb, matched
to the nature sound in the mix: frog (rain/downpour/thunderstorm), whale
(ocean), bird (wind), fox (campfire), duck (stream), firefly (crickets), and a
curled sleeping cat with a floating "z" as the default for noise, ambience or
an empty mix. Drawn as Compose vector art (renders in the screenshot harness,
unlike emoji), overlaid inside the orb's existing 200 dp box so the layout is
unchanged, with one cheap idle breathe/bob and a soft fade-scale swap when the
lead nature sound changes. Pure `critterFor(mix)` mapping is unit-tested; a
gallery screenshot guards all seven.

## 2026-09-12 — Livelier critter animations (0.1.5)

Each companion animal gained characterful idle motion on top of the breathe/bob:
the cat's z-trail drifts up in sequence, the frog blinks every few seconds
(happy arcs) with a throat pulse, the whale's spout puffs droplets, the bird
flutters a wing and blinks, the fox flicks an ear, the duck nods and blinks,
and the firefly's wings shimmer. All driven by one infinite transition exposing
two values (a reversing `breathe` and a sawtooth `clock`), with blinks/twitches derived as smooth Hann-window pulses of the clock — no timers, no extra
transitions, no per-frame allocation, layout untouched. Verified by rendering
pinned key frames (frog open vs mid-blink, cat z-trail early vs late, whale
spout, fox ear) in the screenshot harness. Also lowered the Gradle/Kotlin
daemon heaps (gradle.properties) so the gate fits on the shared build box.

## 2026-09-13 — Codex review of the critter-scenes branch (merged as 0.1.6)

Read-only review of `git diff main..critter-scenes` before merging the bigger
animated "critter scenes" companion into the shipped app.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| 1 | Critical | The scene art overlapped the mix-title text on several scenes (empty-mix cat over "Choose a sound", frog over its title incl. 320 dp, firefly grass into "Crickets") — the y=18 dp offset pushed low art past the orb box into the title. | **Fixed** — lifted and slightly shrank the overlay (`size = 126.dp`, `offset(x = 50, y = -8)`); regenerated all goldens and re-verified the title is clear on the cat, frog (360 & 320 dp) and firefly. |
| 2 | — | Clickability confirmed correct: the critter is a bare Canvas sibling with no pointer modifier, drawn above the orb, so touches pass through. Verified on the emulator (orb toggles when tapped under the overlap). | No change. |
| 3 | — | Performance clean: `breathe`/`clock` read only inside the draw lambda (redraw, not recompose); Paths reused from a pool; fixed-range loops, no per-frame collection allocation. | No change. |
| 4 | Low (follow-up) | `softGlow()` builds a fresh `Brush.radialGradient` per call (1–4×/frame, firefly worst), mirroring the shipped `Critter.kt` pattern — bounded, not a leak, but a GC-pressure follow-up if low-end profiling shows it. | Accepted; noted for follow-up. |
| 5 | Nit | Kover-exclusion comment for `ui.critterscenes` said "never referenced by the shipped app", untrue once wired into HomeScreen. | **Fixed** — comment corrected (drawing-only Compose, verified by the screenshot harness). |

Correctness/layout otherwise clean (exhaustive `when(kind)`, safe `variant` default, no NaN/div-by-zero, 320 dp fine). Merged to main as 0.1.6; the parallel-install identity (`.scenes` / "Hush Scenes") was reverted for the real release.

## 2026-09-16 — Codex review of the wide-screen layout (0.2.0)

Review of the uncommitted two-pane layout (`HomeLayout`, the split
`HomeScreen`, `CatalogSection(columns)`, the removed portrait lock, four wide
screenshot references) against `specs/ui.md`, **Layout**.

| # | Severity | Finding | Resolution |
|---|---|---|---|
| 1 | High | Sheet bodies never scrolled (`HushBottomSheet` was a plain `Column`); the timer sheet is taller than a landscape phone's 360 dp window, so its lower controls were unreachable — masked until now by the portrait lock. | **Fixed** — the sheet body scrolls. Verified on the emulator at 914 × 411 dp: one swipe reaches "Start timer". |
| 2 | Medium | The three-column floor contradicts the 104 dp tile minimum between 600 and 651 dp, and the column breakpoints are really 838 / 1034 / 1154 dp, not the "from 840" the spec said. | **Fixed in the docs** — the floor is intentional (a 600 dp catalog pane *is* a 320 dp phone, tiles as narrow as 87 dp); spec, KDoc and tests now carry the exact numbers, including 652 dp where tiles reach 104. |
| 3 | Medium | `rememberScrollState()` inside the `when` branches lost every scroll position on a resize or fold across 600 dp. | **Fixed** — the compact, playback, catalog and scenes scroll states are hoisted above the split. |
| 4 | Medium | With the lock gone, a landscape phone's side navigation bar or cutout overlapped the body: only the status bar (header) and the bottom navigation bar (volume bar) were padded. | **Fixed** — horizontal `safeDrawing` insets come off the usable width before the split is decided and are padded once around the shared column (`windowInsetsPadding` consumes them, so the bar's bottom padding is unchanged); the aurora stays edge-to-edge. Verified on the rotated emulator with its cutout. |
| 5 | Medium | A width-only split cannot avoid an occluding hinge (Surface Duo class); a flat foldable is fine. | **Accepted** — spec records it as a non-target. |
| 6 | Low | `HomeLayoutTest` skipped the 600–651 dp range and the exact breakpoints, and did not prove the grid is packed as tightly as the tile minimum allows. | **Fixed** — tests for 651/652, 837/838, 1033/1034, 1153/1154, the pane-clamp edges 667/668 and 1046/1047, and a maximal-packing property over 652–2400 dp. |

All-clear per Codex: `BoxWithConstraints` at the root is fine for this split
(reacts to real constraints on rotation and multi-window; a posture library is
not needed for it), `Arrangement.Center` on the scrollable playback pane is
correct, the extraction leaves the phone column byte-identical (all 25 phone
references still validate), no extra manifest flags are needed for targetSdk 36.
Also exercised on the emulator by overriding the logical display: 1280 × 800
and 800 × 1280 dp (10"), 1024 × 600 and 600 × 1024 dp (7"), plus a real
rotation with playback running — no crash, service and wake lock intact.

## 2026-09-16 — Codex review of the header volume popover

The pinned master-volume bar took a quarter of a landscape phone and 1280 dp
of track on a tablet; it became a speaker button in the header that opens an
anchored popover (`specs/ui.md`, Home items 1 and 6).

| # | Severity | Finding | Resolution |
|---|---|---|---|
| 1 | Medium | With three fixed controls on the right and an unweighted wordmark, a large font scale on a 320 dp phone could squeeze the settings button off the edge. | **Fixed** — the wordmark takes the slack (`weight(1f)`, single line, ellipsis); a 320 dp × 1.5 font-scale reference with the widest pill guards it. |
| 2 | Medium | `HushSlider` has no keyboard focus or key handling, so a hardware keyboard can open the popover but not adjust it (inherited by every slider; TalkBack adjusts through `setProgress`). | **Accepted, follow-up** — noted in the spec's accessibility section; to fix for all sliders at once. |
| 3 | Low | The position provider never clamped vertically; the platform's own clipping would have saved a short window. | **Fixed** — the origin is clamped on both axes; tested. |
| 4 | Low | Test names overstated containment; inset anchors, exact fit and short windows were untested. | **Fixed** — renamed and extended. |

Cleared: `PopupProperties(focusable = true)` + `onDismissRequest` handle outside tap
and Back; `remember` state is right for a transient menu (it closes on rotation);
the anchor is the inset header itself, no double-counted status bar; zero cost
while closed; inset spacers and snackbar padding are independent. Verified on
the emulator in both orientations: open, drag, outside-tap close, Back close.

