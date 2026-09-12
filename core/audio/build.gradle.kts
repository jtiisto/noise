plugins {
    id("noise.android.library")
}

// DSP generators + MixRenderer are pure Kotlin (JVM-testable); only
// AudioTrackEngine touches android.media.
dependencies {
    api(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
}
