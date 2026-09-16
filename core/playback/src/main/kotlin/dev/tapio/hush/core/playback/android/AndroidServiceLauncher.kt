package dev.tapio.hush.core.playback.android

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.tapio.hush.core.playback.ServiceLauncher
import dev.tapio.hush.core.playback.service.HushPlaybackService

/** Starts the media foreground service. Device glue only. */
class AndroidServiceLauncher(context: Context) : ServiceLauncher {

    private val appContext = context.applicationContext

    override fun ensureStarted() {
        // Safe to call repeatedly: an already-running service just gets another
        // onStartCommand, which returns START_STICKY and changes nothing.
        try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, HushPlaybackService::class.java),
            )
        } catch (e: IllegalStateException) {
            // API 31+ throws ForegroundServiceStartNotAllowedException (an
            // IllegalStateException) when the app is in the background and no
            // exemption applies — e.g. a focus-GAIN resume long after the
            // activity went away. Playback still runs in-process; the service
            // will be started on the next user-driven play. Never crash for it.
            Log.w(TAG, "Foreground service start not allowed right now; playing without it", e)
        }
    }

    private companion object {
        const val TAG = "HushServiceLauncher"
    }
}
