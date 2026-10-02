// SPDX-License-Identifier: GPL-3.0-or-later

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.roborazzi) apply false
    alias(libs.plugins.spotless)
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    kotlin {
        target("**/src/**/*.kt")
        targetExclude("**/build/**", ".*/**")
        ktlint(ktlintVersion)
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        targetExclude("**/build/**", ".*/**")
        ktlint(ktlintVersion)
    }
}

// Everything a push has to pass: formatting, detekt, the unit tests and the
// goldens of the settings screen. The pre-push hook and CI run this task. No
// APK is assembled here.
tasks.register("gate") {
    group = "verification"
    description = "Format, detekt, the unit tests and the goldens: what a push has to pass."
    dependsOn("spotlessCheck", ":app:detekt", ":app:testDebugUnitTest", ":app:verifyRoborazziDebug")
}

/**
 * Release signing, read from an optional `keystore.properties` in the project
 * root. Git ignores that file.
 *
 * It may hold `storeFile` (a path relative to the project root), `keyAlias`,
 * `storePassword` and `keyPassword`. The environment variables
 * `SOURCE_STORE_PASSWORD` and `SOURCE_KEY_PASSWORD` take precedence over the
 * two passwords in the file:
 *
 * ```
 * SOURCE_STORE_PASSWORD=... SOURCE_KEY_PASSWORD=... ./gradlew :app:assembleRelease
 * ```
 *
 * Without the file, or without `storeFile` in it, a release build is unsigned.
 * With `storeFile`, a release build is signed with that key, and fails when a
 * password is in neither place. Debug builds use the debug key either way.
 *
 * Android installs an update only when it is signed with the same key as the
 * installed app, so every release of a source needs the same key.
 */
val signing =
    Properties().apply {
        val file = rootProject.file("keystore.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

/** The environment variable when it is set and not blank, otherwise the property from `keystore.properties`. */
fun secret(
    variable: String,
    property: String,
): String? = System.getenv(variable)?.takeIf { it.isNotBlank() } ?: signing.getProperty(property)

val store: String? = signing.getProperty("storeFile")

/** The version code for a semver name: 1.2.3 is 10203. */
fun versionCodeOf(name: String): Int {
    val parts = name.split(".").map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    return parts.getOrElse(0) { 0 } * 10_000 + parts.getOrElse(1) { 0 } * 100 + parts.getOrElse(2) { 0 }
}

subprojects {
    val module = this
    plugins.withId("com.android.application") {
        module.extensions.configure<ApplicationExtension>("android") {
            compileSdk = 36
            buildToolsVersion = "36.1.0"
            defaultConfig.minSdk = 26
            compileOptions.sourceCompatibility = JavaVersion.VERSION_17
            compileOptions.targetCompatibility = JavaVersion.VERSION_17
            signingConfigs.create("source") {
                if (store != null) {
                    storeFile = rootProject.file(store)
                    keyAlias = signing.getProperty("keyAlias")
                    storePassword = secret("SOURCE_STORE_PASSWORD", "storePassword")
                    keyPassword = secret("SOURCE_KEY_PASSWORD", "keyPassword")
                }
            }
            // no signing config when keystore.properties names no keystore, so
            // the release build is unsigned
            buildTypes.named("release") {
                signingConfig = signingConfigs.getByName("source").takeIf { store != null }
            }
        }
        module.extensions.configure<ApplicationAndroidComponentsExtension>("androidComponents") {
            finalizeDsl { android ->
                android.defaultConfig.versionCode = versionCodeOf(android.defaultConfig.versionName.orEmpty())
            }
        }
    }
    // all of src, so every source set is analyzed; detekt's default covers
    // main and test only
    plugins.withId("io.gitlab.arturbosch.detekt") {
        module.extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.file("detekt.yml"))
            source.setFrom(module.files("src"))
        }
    }
}
