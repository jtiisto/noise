package dev.jtiisto.noise.core.playback.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import dev.jtiisto.noise.core.playback.AudioFocusGate
import dev.jtiisto.noise.core.playback.FocusEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [AudioFocusGate] over [AudioManager]. Device glue only — the controller
 * decides what each event means.
 *
 * `willPauseWhenDucked = false` because we duck in the engine (a smooth gain
 * ramp) rather than letting the framework pause us for a notification blip.
 */
class AndroidAudioFocusGate(context: Context) : AudioFocusGate {

    private val appContext = context.applicationContext
    private val audioManager = requireNotNull(appContext.getSystemService(AudioManager::class.java)) {
        "AudioManager unavailable"
    }

    private val _events = MutableSharedFlow<FocusEvent>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<FocusEvent> = _events.asSharedFlow()

    private val focusRequest: AudioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { change -> emit(change.toFocusEvent()) }
        .build()

    /** Non-null exactly while unplug monitoring is on; see [setNoisyMonitoring]. */
    private var noisyReceiver: BroadcastReceiver? = null

    override fun request(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    override fun abandon() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    override fun setNoisyMonitoring(enabled: Boolean) {
        if (enabled) registerNoisyReceiver() else unregisterNoisyReceiver()
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    emit(FocusEvent.BECOMING_NOISY)
                }
            }
        }
        // NOT_EXPORTED is required from API 33; ACTION_AUDIO_BECOMING_NOISY is a
        // protected system broadcast so it still reaches us.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyReceiver = receiver
    }

    private fun unregisterNoisyReceiver() {
        val receiver = noisyReceiver ?: return
        noisyReceiver = null
        appContext.unregisterReceiver(receiver)
    }

    private fun emit(event: FocusEvent?) {
        if (event != null) _events.tryEmit(event)
    }

    private fun Int.toFocusEvent(): FocusEvent? = when (this) {
        AudioManager.AUDIOFOCUS_LOSS -> FocusEvent.LOSS
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusEvent.LOSS_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusEvent.DUCK
        AudioManager.AUDIOFOCUS_GAIN -> FocusEvent.GAIN
        else -> null
    }

    private companion object {
        const val EVENT_BUFFER = 8
    }
}
