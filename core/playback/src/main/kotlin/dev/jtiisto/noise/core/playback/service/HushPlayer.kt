package dev.jtiisto.noise.core.playback.service

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.jtiisto.noise.core.playback.PlaybackController
import dev.jtiisto.noise.core.playback.PlaybackState

/**
 * A read-only mirror of [PlaybackController] for the media session. It holds
 * no state of its own: [getState] projects the controller's current state, and
 * the service calls [invalidateState] whenever something the notification
 * shows has changed. Commands travel the other way, into the controller.
 *
 * Device glue — excluded from coverage, no decisions here.
 */
@UnstableApi
internal class HushPlayer(
    private val controller: PlaybackController,
    private val onStopRequested: () -> Unit,
    looper: Looper,
) : SimpleBasePlayer(looper) {

    private val commands: Player.Commands = Player.Commands.Builder()
        // Play/pause and stop are the only things the user may do from a
        // notification or a headset; seeking and volume are meaningless for an
        // endless generated stream.
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            // Without this, SimpleBasePlayer.release() is a no-op: it checks the
            // available commands first, so neither its own base cleanup nor
            // handleRelease() ran when the service was destroyed — the media
            // session leaked (playback review #7, ported from Notch 2026-09-15).
            Player.COMMAND_RELEASE,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
        )
        .build()

    override fun getState(): State {
        val playback = controller.state.value
        val builder = State.Builder().setAvailableCommands(commands)
        return if (playback.mix.isEmpty) {
            // No playlist means the session has nothing to show; STATE_IDLE is
            // the only legal playback state for an empty playlist.
            builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        } else {
            builder
                .setPlaylist(listOf(mediaItemData(playback)))
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(playback.isPlaying, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }
    }

    /** [invalidateState] is protected; the service needs a way in. */
    fun refresh() = invalidateState()

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.pause()
        onStopRequested()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleRelease(): ListenableFuture<*> {
        controller.pause()
        return Futures.immediateVoidFuture()
    }

    private fun mediaItemData(playback: PlaybackState): MediaItemData {
        val metadata = MediaMetadata.Builder()
            .setTitle(playback.mix.title(APP_TITLE))
            .setArtist(subtitle(playback))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        return MediaItemData.Builder(MEDIA_ITEM_UID)
            .setMediaItem(MediaItem.Builder().setMediaId(MEDIA_ITEM_UID).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .build()
    }

    private fun subtitle(playback: PlaybackState): String? {
        val timer = playback.timer ?: return null
        return "Sleep timer · ${remainingMinutes(timer.remainingMillis)} min"
    }

    companion object {
        const val MEDIA_ITEM_UID = "hush-mix"
        private const val APP_TITLE = "Hush"

        /** Rounded up so a timer never displays "0 min" while it is still running. */
        fun remainingMinutes(remainingMillis: Long): Int =
            ((remainingMillis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()

        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
