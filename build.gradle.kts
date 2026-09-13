plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.screenshot) apply false
    alias(libs.plugins.kover)
}

// Merged coverage across all modules; gated by githooks/pre-push via
// koverVerifyAggregated. Device-only glue and Composables are excluded so the
// metric tracks unit-testable logic (no Compose UI test rig in this project).
kover {
    merge {
        allProjects()
        createVariant("aggregated") {
            add("debug", optional = true)
        }
    }

    reports {
        filters {
            excludes {
                classes(
                    "*BuildConfig",
                    "*ComposableSingletons*",
                    "*ModuleKt",
                    "dev.jtiisto.noise.MainActivity",
                    "dev.jtiisto.noise.HushApplication",
                    // Device-only glue: AudioTrack sink, foreground service,
                    // media session player, audio-focus receiver, DataStore
                    // wrapper. None can execute off a device; every decision
                    // they would have made lives in MixRenderer /
                    // DefaultPlaybackController / PersistedStateCodec, which
                    // are counted.
                    "dev.jtiisto.noise.core.audio.AudioTrackEngine",
                    "dev.jtiisto.noise.core.audio.AudioTrackEngine$*",
                    "dev.jtiisto.noise.core.playback.service.*",
                    "dev.jtiisto.noise.core.playback.android.*",
                )
                packages(
                    "dev.jtiisto.noise.ui.theme",
                    // Draft "critter scenes" art — a parallel design exploration
                    // (static Compose vector mockups, rendered only by the
                    // screenshot harness). It is never referenced by the shipped
                    // app; like the theme package and Composables above it is
                    // excluded from the production coverage metric.
                    "dev.jtiisto.noise.ui.critterscenes",
                )
                annotatedBy("androidx.compose.runtime.Composable")
            }
        }
        variant("aggregated") {
            verify {
                rule {
                    // Baseline-derived gate: 93.5 % aggregated line coverage
                    // measured 2026-09-12 at the first integrated build (device
                    // glue and Composables excluded above). Raise as coverage
                    // improves, never lower without a deliberate decision.
                    minBound(90)
                }
            }
        }
    }
}
