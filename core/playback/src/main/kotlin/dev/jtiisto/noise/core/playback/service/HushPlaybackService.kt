package dev.jtiisto.noise.core.playback.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.jtiisto.noise.core.playback.PlaybackController
import dev.jtiisto.noise.core.playback.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

/**
 * Keeps the process foreground while sound is playing and exposes the media
 * session (lock screen, headset buttons, notification).
 *
 * The service decides nothing: it mirrors [PlaybackController] into
 * [HushPlayer] and forwards commands back. Device glue — excluded from
 * coverage.
 */
@UnstableApi
class HushPlaybackService : MediaSessionService() {

    private val controller: PlaybackController by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.hush_playback_channel_name)
                .build(),
        )

        val player = HushPlayer(
            controller = controller,
            onStopRequested = { stopSelf() },
            looper = mainLooper,
        )
        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .apply { sessionActivityIntent()?.let { setSessionActivity(it) } }
            .build()

        observeController(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Restart after a process kill; the controller restores itself from
        // disk and resumes if it was playing.
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away must not stop an all-night sleep sound, but a
        // service left over from a paused session should go.
        if (!controller.state.value.isPlaying) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * Two subscriptions: one refreshes the session whenever something the
     * notification shows changed (title, play state, whole minutes left — not
     * every one-second tick), the other retires the service once it has been
     * idle long enough that the user is clearly done.
     */
    private fun observeController(player: HushPlayer) {
        scope.launch {
            controller.state
                .map { state ->
                    NotificationKey(
                        isPlaying = state.isPlaying,
                        title = state.mix.title(),
                        timerMinutes = state.timer?.let { HushPlayer.remainingMinutes(it.remainingMillis) },
                    )
                }
                .distinctUntilChanged()
                .collect { player.refresh() }
        }
        scope.launch {
            controller.state
                .map { it.isPlaying }
                .distinctUntilChanged()
                .collectLatest { isPlaying ->
                    if (isPlaying) return@collectLatest
                    delay(IDLE_STOP_MILLIS)
                    stopSelf()
                }
        }
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // Created here rather than left to Media3 so the channel carries a
        // description; Media3 only creates it when one does not exist yet.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.hush_playback_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.hush_playback_channel_description) },
        )
    }

    private fun sessionActivityIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            0,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private data class NotificationKey(
        val isPlaying: Boolean,
        val title: String,
        val timerMinutes: Int?,
    )

    private companion object {
        const val CHANNEL_ID = "hush_playback"
        const val SESSION_ID = "hush"
        const val IDLE_STOP_MILLIS = 60_000L
    }
}
