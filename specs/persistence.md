# Persistence spec

Preferences DataStore file `hush.preferences_pb` (Android-only wrapper around
a pure mapping):

| Key | Type | Meaning |
|---|---|---|
| `mix` | String | `id:gain,id:gain,…` in layer order (gain as 3-decimal float) |
| `master_volume` | Float | 0..1 |
| `fade_out_seconds` | Int | sleep-timer fade window |
| `mix_with_other_apps` | Boolean | skip audio focus |
| `last_timer_minutes` | Int | pre-selected preset |
| `was_playing` | Boolean | resume after process death |
| `timer_end_at` | Long | epoch ms; 0 = none |
| `timer_total_ms` | Long | for progress; 0 = none |

`PersistedState` ⇄ `Preferences` mapping is pure (`PersistedStateCodec`), unit
tested for round-trips, unknown sound ids (dropped), malformed entries
(ignored), and defaults. Writes are debounced 300 ms in the controller and
flushed on `pause()`/`startTimer()`/`cancelTimer()`, on sleep-timer completion
and on any pause forced by audio focus.

Decoding is **total** — a bad value never throws, because it is read during
app start where nothing could handle the failure. Specifically:

- `mix` entries are `id:gain` pairs; an entry that is not exactly two fields,
  whose id is not in the catalog, or whose gain does not parse is dropped.
  Duplicates collapse (last gain wins) and anything past `Mix.MAX_LAYERS` is
  ignored.
- Gains and `master_volume` are clamped to 0..1; a non-finite `master_volume`
  falls back to the default.
- `fade_out_seconds` is clamped to the range of `FADE_OPTIONS_SECONDS`
  (15..120), `last_timer_minutes` to `TIMER_MIN_MINUTES..TIMER_MAX_MINUTES`
  (5..480), and both timer millisecond fields to `>= 0`.
- A key holding the wrong value type is treated as absent rather than
  throwing a `ClassCastException`.
- A corrupt DataStore file is replaced with an empty one
  (`ReplaceFileCorruptionHandler`): losing the last mix beats failing to
  start.
