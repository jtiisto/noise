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
flushed on `pause()`/`startTimer()`/`cancelTimer()`.
