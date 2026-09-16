package dev.tapio.hush.di

import dev.tapio.hush.core.audio.AudioEngine
import dev.tapio.hush.core.audio.AudioTrackEngine
import dev.tapio.hush.core.audio.EngineConfig
import dev.tapio.hush.crash.CrashReportStore
import dev.tapio.hush.crash.FileCrashReportStore
import dev.tapio.hush.ui.home.HomeViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The app module: the one binding the playback module leaves to the app (the
 * engine, whose implementation and tuning live in `core/audio`) plus the
 * UI-facing ViewModel. `PlaybackController` itself comes from `playbackModule`.
 */
val appModule = module {
    // One engine for the process lifetime: it owns the audio thread and the
    // generator state, and the controller restores playback through it at
    // start-up before any UI exists.
    single<AudioEngine> { AudioTrackEngine(EngineConfig()) }

    // Same file the uncaught-exception handler wrote to; the store itself holds
    // no state, so reading it back through a second instance is safe.
    single<CrashReportStore> { FileCrashReportStore.forApp(get()) }

    viewModel { HomeViewModel(controller = get(), crashReports = get()) }
}
