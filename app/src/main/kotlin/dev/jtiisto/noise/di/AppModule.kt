package dev.jtiisto.noise.di

import dev.jtiisto.noise.core.playback.PlaybackController
import dev.jtiisto.noise.ui.home.HomeViewModel
import dev.jtiisto.noise.ui.preview.FakePlaybackController
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * The app module: UI-facing bindings only.
 *
 * The `PlaybackController` binding below is a placeholder so the UI can run
 * standalone. It has no audio, no service and no clock.
 */
val appModule = module {
    // TODO(integration): replaced by playbackModule + AudioTrackEngine binding
    single<PlaybackController> { FakePlaybackController() }

    viewModel { HomeViewModel(controller = get()) }
}
