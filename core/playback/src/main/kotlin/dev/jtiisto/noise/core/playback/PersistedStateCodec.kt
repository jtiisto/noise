package dev.jtiisto.noise.core.playback

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import dev.jtiisto.noise.core.model.SoundLayer
import java.util.Locale

/**
 * Pure `Preferences` ⇄ [PersistedState] mapping. Kept out of the DataStore
 * wrapper so the interesting half is a plain JVM unit test —
 * `androidx.datastore.preferences.core.Preferences` needs no Android runtime.
 *
 * Decoding is total: anything unparseable is dropped and replaced by the
 * default rather than throwing. A corrupt preference must never stop the app
 * from starting; the worst acceptable outcome is "your last mix was lost".
 *
 * Key names are the contract with the on-disk file — see specs/persistence.md.
 * Never rename one without a migration.
 */
object PersistedStateCodec {
    val MIX: Preferences.Key<String> = stringPreferencesKey("mix")
    val MASTER_VOLUME: Preferences.Key<Float> = floatPreferencesKey("master_volume")
    val FADE_OUT_SECONDS: Preferences.Key<Int> = intPreferencesKey("fade_out_seconds")
    val MIX_WITH_OTHER_APPS: Preferences.Key<Boolean> = booleanPreferencesKey("mix_with_other_apps")
    val LAST_TIMER_MINUTES: Preferences.Key<Int> = intPreferencesKey("last_timer_minutes")
    val WAS_PLAYING: Preferences.Key<Boolean> = booleanPreferencesKey("was_playing")
    val TIMER_END_AT: Preferences.Key<Long> = longPreferencesKey("timer_end_at")
    val TIMER_TOTAL_MS: Preferences.Key<Long> = longPreferencesKey("timer_total_ms")

    private const val LAYER_SEPARATOR = ","
    private const val FIELD_SEPARATOR = ":"

    private val defaults = PersistedState()
    private val defaultSettings = PlaybackSettings()

    /** Fade windows outside this range would be either inaudible or longer than short timers. */
    private val FADE_RANGE = PlaybackSettings.FADE_OPTIONS_SECONDS.min()..PlaybackSettings.FADE_OPTIONS_SECONDS.max()
    private val TIMER_RANGE = PlaybackSettings.TIMER_MIN_MINUTES..PlaybackSettings.TIMER_MAX_MINUTES

    fun decode(preferences: Preferences): PersistedState = PersistedState(
        mix = decodeMix(preferences.readOrNull(MIX)),
        masterVolume = preferences.readOrNull(MASTER_VOLUME)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            ?: defaults.masterVolume,
        settings = PlaybackSettings(
            fadeOutSeconds = preferences.readOrNull(FADE_OUT_SECONDS)?.coerceIn(FADE_RANGE)
                ?: defaultSettings.fadeOutSeconds,
            mixWithOtherApps = preferences.readOrNull(MIX_WITH_OTHER_APPS) ?: defaultSettings.mixWithOtherApps,
            lastTimerMinutes = preferences.readOrNull(LAST_TIMER_MINUTES)?.let { minutes ->
                // 0 is the "until cancelled" choice, not a too-short timer.
                if (minutes == PlaybackSettings.TIMER_UNTIL_CANCELLED) minutes else minutes.coerceIn(TIMER_RANGE)
            } ?: defaultSettings.lastTimerMinutes,
        ),
        wasPlaying = preferences.readOrNull(WAS_PLAYING) ?: defaults.wasPlaying,
        timerEndAtEpochMillis = preferences.readOrNull(TIMER_END_AT)?.coerceAtLeast(0L)
            ?: defaults.timerEndAtEpochMillis,
        timerTotalMillis = preferences.readOrNull(TIMER_TOTAL_MS)?.coerceAtLeast(0L)
            ?: defaults.timerTotalMillis,
    )

    /**
     * `Preferences.get` casts blindly, so a key that somehow holds the wrong
     * type would throw on read — during app start, before anything can handle
     * it. Treat a type mismatch as "absent" instead.
     */
    private inline fun <reified T : Any> Preferences.readOrNull(key: Preferences.Key<T>): T? =
        asMap()[key] as? T

    fun encode(state: PersistedState, into: MutablePreferences) {
        into[MIX] = encodeMix(state.mix)
        into[MASTER_VOLUME] = state.masterVolume
        into[FADE_OUT_SECONDS] = state.settings.fadeOutSeconds
        into[MIX_WITH_OTHER_APPS] = state.settings.mixWithOtherApps
        into[LAST_TIMER_MINUTES] = state.settings.lastTimerMinutes
        into[WAS_PLAYING] = state.wasPlaying
        into[TIMER_END_AT] = state.timerEndAtEpochMillis
        into[TIMER_TOTAL_MS] = state.timerTotalMillis
    }

    /** `"rain:0.700,brown:0.400"` — layer order is display order and must round-trip. */
    fun encodeMix(mix: Mix): String = mix.layers.joinToString(LAYER_SEPARATOR) { layer ->
        layer.id.key + FIELD_SEPARATOR + String.format(Locale.ROOT, "%.3f", layer.gain)
    }

    fun decodeMix(encoded: String?): Mix {
        if (encoded.isNullOrBlank()) return Mix.EMPTY
        val layers = LinkedHashMap<SoundId, SoundLayer>()
        for (entry in encoded.split(LAYER_SEPARATOR)) {
            val parts = entry.split(FIELD_SEPARATOR)
            if (parts.size != 2) continue
            // Unknown ids are catalog entries that no longer exist: drop them
            // instead of failing the whole restore.
            val id = SoundId.fromKey(parts[0].trim()) ?: continue
            val gain = parts[1].trim().toFloatOrNull()?.takeIf { it.isFinite() } ?: continue
            if (layers.size >= Mix.MAX_LAYERS && !layers.containsKey(id)) continue
            layers[id] = SoundLayer(id, gain.coerceIn(0f, 1f))
        }
        return Mix(layers.values.toList())
    }
}
