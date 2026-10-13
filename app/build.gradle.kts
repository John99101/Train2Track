import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Local release-signing config (keystore.properties + the .jks are git-ignored).
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.mvvtracker.app"
    compileSdk = 36

    defaultConfig {
        manifestPlaceholders += mapOf()
        applicationId = "com.mvvtracker.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.5.0"

        // Live map data source: transitous (open MOTIS instance, no API key).
        // /api/v1/map/trips returns active trip segments (S-Bahn/U-Bahn/regional/
        // long-distance) with track geometry + real-time timings, from which we
        // compute each train's current position. Community-run but open & stable.
        buildConfigField("String", "LIVE_MAP_API_BASE", "\"https://api.transitous.org/\"")

        // Train2Track server: compact per city snapshots, track geometries and line
        // routes from transitous (functions/lib/liveApi.js, Hosting rewrite /api/**).
        // The app falls back to LIVE_MAP_API_BASE directly when it is unreachable.
        buildConfigField("String", "PROXY_API_BASE", "\"https://example.invalid/\"")

        // AdMob ids: Google's public TEST ids by default so debug builds never
        // serve live ads (AdMob policy forbids clicking your own live ads while
        // testing). The real ids are swapped in for the release build below.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        buildConfigField("String", "ADMOB_NATIVE_UNIT", "\"ca-app-pub-3940256099942544/2247696110\"")
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }

            // Native debug symbols into the App Bundle, so Play Console can read
            // crashes in native libraries (needs the NDK installed in Android Studio).
            ndk { debugSymbolLevel = "SYMBOL_TABLE" }

            // Real AdMob ids (Train2Track AdMob account) - live ads only in release.
            manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
            buildConfigField("String", "ADMOB_NATIVE_UNIT", "\"ca-app-pub-3940256099942544/2247696110\"")
        }
    }

    // Play splits an App Bundle by language and installs only the phone's system
    // language, so the in-app switch to German found no German texts on an
    // English phone. Ship every language in the base APK instead.
    bundle {
        language {
            enableSplit = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    // collectAsStateWithLifecycle / LifecycleStartEffect: screens stop loading
    // (live data, Firestore listeners) when the app goes to the background.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    // Extended Material icons (Map, Report, AdminPanelSettings, ... used in
    // the bottom navigation). R8 strips the unused ones in release builds.
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-compiler:2.59.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Room (local cache)
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // DataStore for GDPR consent + preferences
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Firebase (anonymous auth, Firestore backend). The server functions are
    // called over plain HTTPS (data/remote/ServerFunctions): Google's Functions
    // library would bring the Instance ID component and with it a registration
    // with Google's messaging service, which the app does not use.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-analytics-ktx") // consent-gated, see privacy module
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1") // Task.await()

    // App Check: attests that requests come from the genuine app binary
    // (Play Integrity in release; a debug provider so emulators/dev builds work).
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")

    // OpenStreetMap (no API key) - base map for the live train view.
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Google AdMob (native ads). Uses Google's public TEST ad unit ids until a
    // real AdMob account is connected - see AdMobIds.
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    // Google's consent dialog for advertising (certified for the IAB framework):
    // no ad is requested before the user answered it, see privacy/AdsConsent.
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager: the app has no background job any more (it used to tidy old
    // local reports every 15 minutes). Only kept to cancel that old job once on
    // phones that update; the ads SDK brings WorkManager along anyway.
    implementation("androidx.work:work-runtime:2.9.1")

    testImplementation("junit:junit:4.13.2")
}
