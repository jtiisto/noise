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
