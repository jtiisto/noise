package dev.tapio.hush.core.playback.di

import dev.tapio.hush.core.playback.AudioFocusGate
import dev.tapio.hush.core.playback.Clock
import dev.tapio.hush.core.playback.DefaultPlaybackController
import dev.tapio.hush.core.playback.PlaybackController
import dev.tapio.hush.core.playback.ServiceLauncher
import dev.tapio.hush.core.playback.WakeLock
import dev.tapio.hush.core.playback.StateStore
import dev.tapio.hush.core.playback.android.AndroidAudioFocusGate
import dev.tapio.hush.core.playback.android.AndroidServiceLauncher
import dev.tapio.hush.core.playback.android.AndroidWakeLock
import dev.tapio.hush.core.playback.android.DataStoreStateStore
import dev.tapio.hush.core.playback.android.SystemEpochClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier for the process-lifetime scope the controller runs its timers and saves on. */
val ApplicationScope = named("hush-application-scope")

/**
 * Playback wiring.
 *
 * **This module does not provide [dev.tapio.hush.core.audio.AudioEngine]** —
 * the app owns that binding because the engine implementation (and its
 * `EngineConfig`) lives in `core/audio` and is chosen per build. Start Koin
 * with both:
 *
 * ```kotlin
 * startKoin {
 *     androidContext(this@HushApplication)
 *     modules(audioModule, playbackModule)   // audioModule must bind AudioEngine
 * }
 * ```
 *
 * The controller is created at start so playback state is restored (and
 * playback resumed after a process kill) without waiting for the UI.
 */
val playbackModule = module {
    single<Clock> { SystemEpochClock() }
    single<StateStore> { DataStoreStateStore(androidContext()) }
    single<AudioFocusGate> { AndroidAudioFocusGate(androidContext()) }
    single<ServiceLauncher> { AndroidServiceLauncher(androidContext()) }
    single<WakeLock> { AndroidWakeLock(androidContext()) }

    // Main.immediate: the controller is main-thread confined, and immediate
    // dispatch keeps UI-originated commands synchronous.
    single<CoroutineScope>(ApplicationScope) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    single<PlaybackController>(createdAtStart = true) {
        DefaultPlaybackController(
            engine = get(),
            store = get(),
            focus = get(),
            serviceLauncher = get(),
            wakeLock = get(),
            clock = get(),
            scope = get(ApplicationScope),
        )
    }
}
