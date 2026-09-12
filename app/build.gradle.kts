import java.util.Properties

plugins {
    id("noise.android.application")
    alias(libs.plugins.screenshot)
}

// Release signing comes from local.properties (untracked): hush.keystore,
// hush.keystorePassword, hush.keyAlias, hush.keyPassword. Missing keys leave
// the release build unsigned (a clean clone must still build).
val localProperties: Properties = Properties().apply {
    val file = rootProject.layout.projectDirectory.file("local.properties").asFile
    if (file.exists()) file.inputStream().use { load(it) }
}
val keystorePath = localProperties.getProperty("hush.keystore")?.trim().orEmpty()

android {
    namespace = "dev.jtiisto.noise"

    defaultConfig {
        applicationId = "dev.jtiisto.noise"
        versionCode = 4
        versionName = "0.1.3"
    }

    signingConfigs {
        if (keystorePath.isNotEmpty()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = localProperties.getProperty("hush.keystorePassword")
                keyAlias = localProperties.getProperty("hush.keyAlias")
                keyPassword = localProperties.getProperty("hush.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Modest hardware: shrink code and resources, strip unused icons.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePath.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    // VERSION_NAME/VERSION_CODE for the crash report header: reading them back
    // out of PackageManager would mean the API-33-deprecated getPackageInfo.
    buildFeatures.buildConfig = true

    experimentalProperties["android.experimental.enableScreenshotTest"] = true
}

composeCompiler {
    // Marks the cross-module state types stable so screens skip recomposition
    // when their inputs are unchanged (see compose-stability.conf).
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
    // Compiler reports show which composables are skippable; kept out of the
    // build by default, generate with -Pnoise.composeReports=true.
    if (providers.gradleProperty("noise.composeReports").isPresent) {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:audio"))
    implementation(project(":core:playback"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.coroutines.android)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)
}
