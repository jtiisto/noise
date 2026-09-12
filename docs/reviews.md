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

