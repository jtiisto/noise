package dev.tapio.hush.core.playback.android

import android.content.Context
import android.os.PowerManager
import android.util.Log
import dev.tapio.hush.core.playback.WakeLock

/**
 * A `PARTIAL_WAKE_LOCK` over [PowerManager]. Device glue only — the controller
 * decides when it is held (exactly while the engine renders).
 *
 * Not reference counted and guarded by a held-flag so a doubled acquire or
 * release can never leak the lock or throw "under-locked". The permission
 * (`WAKE_LOCK`) is declared in the app manifest.
 */
class AndroidWakeLock(context: Context) : WakeLock {

    private val powerManager =
        context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val lock: PowerManager.WakeLock =
        powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
            setReferenceCounted(false)
        }

    override fun acquire() {
        try {
            // No timeout: an overnight mix legitimately runs for hours, and the
            // controller always releases on pause/stop, so it cannot be leaked.
            if (!lock.isHeld) lock.acquire()
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not acquire wake lock", e)
        }
    }

    override fun release() {
        try {
            if (lock.isHeld) lock.release()
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not release wake lock", e)
        }
    }

    private companion object {
        const val TAG = "hush:playback"
    }
}
