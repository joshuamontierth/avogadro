// AGP 9 compiles Kotlin itself (built-in Kotlin). Declaring the Kotlin plugin here (not applied)
// puts the pinned Kotlin Gradle plugin on the classpath, so AGP uses Kotlin ${libs.versions.kotlin}
// instead of its bundled default.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
