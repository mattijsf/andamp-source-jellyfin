// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.application)
    // golden images of the settings screen
    alias(libs.plugins.roborazzi)
    // the settings screen is drawn with Compose
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "nl.mattix.andamp.pack.jellyfin"

    defaultConfig {
        applicationId = "nl.mattix.andamp.pack.jellyfin"
        targetSdk = 36
        versionName = "0.2.1" // x-release-please-version
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        // BuildConfig.VERSION_NAME is the version the source reports to the
        // player, and to the server in its Authorization header
        buildConfig = true
    }

    testOptions {
        unitTests {
            // for Robolectric, which draws the settings screen and provides
            // org.json and a context for the store
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    // the contract: the AIDL and the types that cross it
    implementation(libs.andamp.source.api)
    // the source side of the contract: the service base, the audio pipe, the
    // launcher entry and stream playback
    implementation(libs.andamp.source.common)
    implementation(libs.kotlinx.coroutines.core)
    // HTTP to the server; the JSON is read with the platform's org.json
    implementation(libs.okhttp)
    // the settings screen: one page in Material 3
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    detektPlugins(libs.detekt.compose.rules)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric for org.json, for a context to keep preferences in, and for
    // the settings screen
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
