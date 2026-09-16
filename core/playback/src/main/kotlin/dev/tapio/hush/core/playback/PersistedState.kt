package dev.tapio.hush.core.playback

import dev.tapio.hush.core.model.Mix

/**
 * Everything that has to survive a process restart. See specs/persistence.md.
 *
 * The timer is stored as a wall-clock end time rather than a remaining
 * duration: the process may be dead for an arbitrary period, and a sleep timer
 * that silently pauses while the phone is killed would keep playing all night.
 */
data class PersistedState(
    val mix: Mix = Mix.EMPTY,
    val masterVolume: Float = DEFAULT_MASTER_VOLUME,
    val settings: PlaybackSettings = PlaybackSettings(),
    val wasPlaying: Boolean = false,
    /** Epoch ms; 0 = no timer. */
    val timerEndAtEpochMillis: Long = 0L,
    /** Total length of the running timer, for progress; 0 = no timer. */
    val timerTotalMillis: Long = 0L,
) {
    companion object {
        const val DEFAULT_MASTER_VOLUME = 0.8f
    }
}

/**
 * Persistence port. The only implementation that touches Android is
 * `DataStoreStateStore`; tests use a fake.
 */
interface StateStore {
    suspend fun load(): PersistedState

    suspend fun save(state: PersistedState)
}
