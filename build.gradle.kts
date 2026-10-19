// Top-level build file
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // KSP replaces kapt for Hilt/Room annotation processing (recommended on
    // Kotlin 2.x). Since KSP 2.3 its version no longer follows Kotlin's.
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
