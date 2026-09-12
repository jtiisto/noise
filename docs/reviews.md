# Review log

Codex full-repo reviews and how each finding was resolved. Accepted findings
(not fixed) stay listed with the reason.

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

Verified on the emulator: `hush:playback` held (uid=dev.jtiisto.noise) while
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
