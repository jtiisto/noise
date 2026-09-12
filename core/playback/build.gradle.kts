plugins {
    id("noise.android.library")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:audio"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.session)
    implementation(libs.media3.common)
    implementation(libs.koin.android)
}
