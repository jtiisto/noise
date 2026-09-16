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
                    "dev.tapio.hush.MainActivity",
                    "dev.tapio.hush.HushApplication",
                    // Device-only glue: AudioTrack sink, foreground service,
                    // media session player, audio-focus receiver, DataStore
                    // wrapper. None can execute off a device; every decision
                    // they would have made lives in MixRenderer /
                    // DefaultPlaybackController / PersistedStateCodec, which
                    // are counted.
                    "dev.tapio.hush.core.audio.AudioTrackEngine",
                    "dev.tapio.hush.core.audio.AudioTrackEngine$*",
                    "dev.tapio.hush.core.playback.service.*",
                    "dev.tapio.hush.core.playback.android.*",
                )
                packages(
                    "dev.tapio.hush.ui.theme",
                    // "Critter scenes" — the animated companion vector art wired
                    // into the home screen. It is drawing-only Compose (Canvas
                    // draw lambdas the @Composable filter cannot see), verified by
                    // the screenshot harness rather than unit tests, so like the
                    // theme package and Composables above it is excluded from the
                    // production coverage metric.
                    "dev.tapio.hush.ui.critterscenes",
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
