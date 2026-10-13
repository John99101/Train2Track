// Top-level build file
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
    id("com.google.dagger.hilt.android") version "2.59.2" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    // KSP replaces kapt for Hilt/Room annotation processing (recommended on
    // Kotlin 2.x). Version's prefix must match the Kotlin version (2.2.10).
    id("com.google.devtools.ksp") version "2.3.6" apply false
}
