import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

// Modest hardware is the target: minSdk 26 covers Android 8.0+ and gives us
// notification channels, AudioFocusRequest and foreground services without
// compat branches. compileSdk 37 is what Compose 1.12 requires.
internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 26
internal const val TARGET_SDK = 36
internal const val BASE_PACKAGE = "dev.tapio.hush"

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

/** Namespace derived from the Gradle path: ":core:audio" -> "dev.tapio.hush.core.audio". */
internal val Project.derivedNamespace: String
    get() = BASE_PACKAGE + path.replace(":", ".")

/** JUnit platform for unit tests plus the shared test dependencies every module gets. */
internal fun Project.configureUnitTests() {
    // DSP statistical tests render seconds of audio; give the fork headroom.
    // Overridable (e.g. in ~/.gradle/gradle.properties) for memory-constrained
    // build boxes where the combined daemon + test-fork footprint must stay
    // under a memory guard: -PnoiseTestHeap=640m.
    val testHeap = providers.gradleProperty("noiseTestHeap").orElse("1g")
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        maxHeapSize = testHeap.get()
    }
    dependencies {
        "testImplementation"(libs.findLibrary("junit-jupiter").get())
        "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
        "testImplementation"(libs.findLibrary("mockk").get())
        "testImplementation"(libs.findLibrary("turbine").get())
        "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
    }
}

/** Compose runtime dependencies for modules that render UI. */
internal fun Project.addComposeDependencies() {
    dependencies {
        "implementation"(platform(libs.findLibrary("compose-bom").get()))
        "implementation"(libs.findLibrary("compose-ui").get())
        "implementation"(libs.findLibrary("compose-ui-graphics").get())
        "implementation"(libs.findLibrary("compose-foundation").get())
        "implementation"(libs.findLibrary("compose-material3").get())
        "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
        "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
    }
}
