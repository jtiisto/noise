package dev.jtiisto.noise.core.playback

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.jtiisto.noise.core.model.Mix
import dev.jtiisto.noise.core.model.SoundId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `Preferences` is a plain JVM class, so the whole mapping is testable without
 * an Android runtime — which is the point of keeping it out of the DataStore
 * wrapper.
 */
class PersistedStateCodecTest {

    private fun roundTrip(state: PersistedState): PersistedState {
        val preferences = mutablePreferencesOf()
        PersistedStateCodec.encode(state, preferences)
        return PersistedStateCodec.decode(preferences)
    }

    @Test
    fun `a full state round-trips`() {
        val state = PersistedState(
            mix = Mix.of(SoundId.RAIN to 0.35f, SoundId.CAMPFIRE to 0.7f, SoundId.WIND to 1f),
            masterVolume = 0.42f,
            settings = PlaybackSettings(fadeOutSeconds = 120, mixWithOtherApps = true, lastTimerMinutes = 90),
            wasPlaying = true,
            timerEndAtEpochMillis = 1_700_000_123_456L,
            timerTotalMillis = 1_800_000L,
        )

        assertEquals(state, roundTrip(state))
    }

    @Test
    fun `an empty mix round-trips`() {
        val state = PersistedState(mix = Mix.EMPTY, wasPlaying = false)

        assertEquals(state, roundTrip(state))
    }

    @Test
    fun `layer order and three-decimal gains survive`() {
        val mix = Mix.of(SoundId.OCEAN to 0.125f, SoundId.PINK to 0.999f)

        assertEquals("ocean:0.125,pink:0.999", PersistedStateCodec.encodeMix(mix))
        assertEquals(mix, PersistedStateCodec.decodeMix("ocean:0.125,pink:0.999"))
    }

    @Test
    fun `empty preferences decode to the defaults`() {
        val decoded = PersistedStateCodec.decode(emptyPreferences())

        assertEquals(PersistedState(), decoded)
        assertTrue(decoded.mix.isEmpty)
        assertEquals(PlaybackSettings(), decoded.settings)
    }

    @Test
    fun `unknown sound ids are dropped`() {
        val mix = PersistedStateCodec.decodeMix("rain:0.5,unicorn:0.5,brown:0.25")

        assertEquals(listOf(SoundId.RAIN, SoundId.BROWN), mix.ids)
    }

    @Test
    fun `malformed mix entries are ignored`() {
        val mix = PersistedStateCodec.decodeMix("rain:0.5,,brown,ocean:notanumber,fan:0.3:0.4,wind:0.2")

        assertEquals(listOf(SoundId.RAIN, SoundId.WIND), mix.ids)
    }

    @Test
    fun `a blank or absent mix decodes to empty`() {
        assertTrue(PersistedStateCodec.decodeMix(null).isEmpty)
        assertTrue(PersistedStateCodec.decodeMix("").isEmpty)
        assertTrue(PersistedStateCodec.decodeMix("   ").isEmpty)
    }

    @Test
    fun `more layers than the limit are truncated and duplicates collapse`() {
        val mix = PersistedStateCodec.decodeMix("rain:0.5,brown:0.5,wind:0.5,fan:0.5")
        assertEquals(listOf(SoundId.RAIN, SoundId.BROWN, SoundId.WIND), mix.ids)

        val deduped = PersistedStateCodec.decodeMix("rain:0.2,rain:0.8")
        assertEquals(listOf(SoundId.RAIN), deduped.ids)
        assertEquals(0.8f, deduped.layer(SoundId.RAIN)!!.gain)
    }

    @Test
    fun `out-of-range gains are clamped`() {
        val mix = PersistedStateCodec.decodeMix("rain:5.0,brown:-2.0")

        assertEquals(1f, mix.layer(SoundId.RAIN)!!.gain)
        assertEquals(0f, mix.layer(SoundId.BROWN)!!.gain)
    }

    @Test
    fun `out-of-range scalars are clamped`() {
        val decoded = PersistedStateCodec.decode(
            preferencesOf(
                PersistedStateCodec.MASTER_VOLUME to 4f,
                PersistedStateCodec.FADE_OUT_SECONDS to 9_000,
                PersistedStateCodec.LAST_TIMER_MINUTES to 2,
                PersistedStateCodec.TIMER_END_AT to -5L,
                PersistedStateCodec.TIMER_TOTAL_MS to -1L,
            ),
        )

        assertEquals(1f, decoded.masterVolume)
        assertEquals(PlaybackSettings.FADE_OPTIONS_SECONDS.max(), decoded.settings.fadeOutSeconds)
        assertEquals(PlaybackSettings.TIMER_MIN_MINUTES, decoded.settings.lastTimerMinutes)
        assertEquals(0L, decoded.timerEndAtEpochMillis)
        assertEquals(0L, decoded.timerTotalMillis)
    }

    @Test
    fun `zero timer minutes is the until-cancelled choice and survives a round-trip`() {
        val decoded = PersistedStateCodec.decode(
            preferencesOf(PersistedStateCodec.LAST_TIMER_MINUTES to PlaybackSettings.TIMER_UNTIL_CANCELLED),
        )
        assertEquals(PlaybackSettings.TIMER_UNTIL_CANCELLED, decoded.settings.lastTimerMinutes)
        assertTrue(decoded.settings.prefersUntilCancelled)

        val encoded = mutablePreferencesOf()
        PersistedStateCodec.encode(decoded, encoded)
        assertEquals(PlaybackSettings.TIMER_UNTIL_CANCELLED, encoded[PersistedStateCodec.LAST_TIMER_MINUTES])
    }

    @Test
    fun `a non-finite master volume falls back to the default`() {
        val decoded = PersistedStateCodec.decode(
            preferencesOf(PersistedStateCodec.MASTER_VOLUME to Float.NaN),
        )

        assertEquals(PersistedState.DEFAULT_MASTER_VOLUME, decoded.masterVolume)
    }

    @Test
    fun `a preference stored under the wrong type is treated as absent`() {
        val decoded = PersistedStateCodec.decode(
            preferencesOf(stringPreferencesKey("master_volume") to "not a float"),
        )

        assertEquals(PersistedState.DEFAULT_MASTER_VOLUME, decoded.masterVolume)
    }

    @Test
    fun `encode overwrites a previous value rather than merging`() {
        val preferences = mutablePreferencesOf()
        PersistedStateCodec.encode(PersistedState(mix = Mix.of(SoundId.RAIN to 0.5f)), preferences)
        PersistedStateCodec.encode(PersistedState(mix = Mix.EMPTY), preferences)

        assertTrue(PersistedStateCodec.decode(preferences).mix.isEmpty)
    }

    @Test
    fun `every persisted key name matches the persistence spec`() {
        assertEquals("mix", PersistedStateCodec.MIX.name)
        assertEquals("master_volume", PersistedStateCodec.MASTER_VOLUME.name)
        assertEquals("fade_out_seconds", PersistedStateCodec.FADE_OUT_SECONDS.name)
        assertEquals("mix_with_other_apps", PersistedStateCodec.MIX_WITH_OTHER_APPS.name)
        assertEquals("last_timer_minutes", PersistedStateCodec.LAST_TIMER_MINUTES.name)
        assertEquals("was_playing", PersistedStateCodec.WAS_PLAYING.name)
        assertEquals("timer_end_at", PersistedStateCodec.TIMER_END_AT.name)
        assertEquals("timer_total_ms", PersistedStateCodec.TIMER_TOTAL_MS.name)
    }
}
