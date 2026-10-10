package dev.tapio.hush.core.playback.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.tapio.hush.core.playback.PlaybackController
import dev.tapio.hush.core.playback.R
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
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.hush_playback_channel_name)
                .build(),
        )

        val player = HushPlayer(
            controller = controller,
            onStopRequested = { stopSelf() },
            looper = mainLooper,
        )
        val session = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .apply { sessionActivityIntent()?.let { setSessionActivity(it) } }
            .build()
        mediaSession = session
        // Media3 only attaches its notification manager to a session once the
        // session is *added* to the service, which normally happens when a
        // Media3 controller binds and onGetSession() answers. Nothing in Hush
        // ever binds — the UI talks to the PlaybackController directly and the
        // system uses the platform session — so without this call the manager
        // never connects, no player event ever reaches it, and the notification
        // stays the bootstrap placeholder below forever (its title frozen at
        // the first play; field bug 2026-10-10). Added here, it posts the rich
        // notification, keeps the title and timer current, and handles the
        // foreground state on play and pause. Released sessions are removed
        // automatically.
        addSession(session)

        observeController(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The OS gives a service started with startForegroundService() ~5 s to
        // call startForeground(); Media3 otherwise only promotes once its
        // player-state listener has run, and on the very first play the
        // POST_NOTIFICATIONS dialog delays that past the deadline — the system
        // then kills the process with ForegroundServiceDidNotStartInTime.
        // Promote immediately, and with whatever notification is already on
        // screen: Media3 promotes by first starting this service with a
        // self-intent and only then posting its rich notification, so the
        // self-intent lands here *after* that, and a placeholder here used to
        // overwrite the rich notification every time (same id) — a title-only
        // row with no timer line, no media style and no controls (field bug
        // #3, 2026-10-10). Re-posting the live notification keeps the content
        // and still satisfies every start's foreground obligation; the
        // placeholder is only for the very first start, before Media3's
        // controller has connected. Never let this throw: a background-start
        // refusal is handled by falling back to Media3's own lifecycle.
        promoteToForeground()
        super.onStartCommand(intent, flags, startId)
        // START_STICKY so a process killed mid-playback is recreated; the
        // controller restores from disk and resumes if it was playing.
        return START_STICKY
    }

    private fun promoteToForeground() {
        val notification = currentNotification() ?: placeholderNotification()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException on a background
            // start we could not satisfy; the launcher already guards the
            // start call, and playback continues in-process regardless.
            android.util.Log.w("HushPlaybackService", "could not enter foreground", e)
        }
    }

    /** The notification currently posted under our id — Media3's, once it has one. */
    private fun currentNotification(): Notification? =
        getSystemService(NotificationManager::class.java)
            ?.activeNotifications
            ?.firstOrNull { it.id == NOTIFICATION_ID }
            ?.notification

    private fun placeholderNotification(): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(controller.state.value.mix.title(getString(R.string.hush_playback_channel_name)))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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
                        timerPaused = state.timer?.isPaused == true,
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
        /** The subtitle says so, and freezing need not change the minutes. */
        val timerPaused: Boolean,
    )

    private companion object {
        const val CHANNEL_ID = "hush_playback"
        const val SESSION_ID = "hush"

        // Media3's DefaultMediaNotificationProvider default id; sharing it lets
        // Media3's rich notification replace our bootstrap one in place.
        const val NOTIFICATION_ID = 1001
        const val IDLE_STOP_MILLIS = 60_000L
    }
}
