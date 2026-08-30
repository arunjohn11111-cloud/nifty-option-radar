plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.niftyradar.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.niftyradar.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    // WHY a checked-in debug keystore: without this, the Android Gradle Plugin generates a
    // fresh random ~/.android/debug.keystore on whatever machine is building. Every GitHub
    // Actions run is a brand-new throwaway VM, so every CI build was signed with a DIFFERENT
    // key — and Android refuses to install an APK over an existing app signed by a different
    // key ("App not installed"). That forced an uninstall before every single update, which
    // wiped the saved Upstox token, the locked radar session and all stored tick history each
    // time (and cost a real day's data at least once). Pinning one keystore in the repo makes
    // every build share a signature, so updates install straight over the previous version and
    // keep their data — on the phone and on the TV alike.
    //
    // Safe to commit: this is a DEBUG-only key that never signs a Play Store release, and it
    // uses the standard, publicly-documented debug credentials ("android"/"androiddebugkey")
    // that every Android debug build in the world already uses — it is not a secret. A real
    // release key, if this app ever needs one, must NOT be handled this way.
    //
    // Nifty Quick Trade pins the same key. That is deliberate and harmless: the two apps have
    // different applicationIds, so they never collide — it just means one less thing to track.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Phase 4: compiles app/src/main/proto/MarketDataFeed.proto (Upstox's Market
// Data Feed V3 schema) into standalone Kotlin/Java classes in package
// com.niftyradar.app.marketdatafeed (see the `option java_*` lines in that
// file). protoc is downloaded automatically at build time — this only needs
// normal internet access (available on the GitHub Actions runner), not
// anything special.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.36.0"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // Core / lifecycle
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    // Jetpack Compose (versions managed by the BOM)
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Secure, encrypted local storage for the Upstox access token (Phase 1)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Networking (Phase 1-3 REST calls + Phase 4's Market Data Feed V3
    // WebSocket both go through OkHttp — it already includes WebSocket
    // support, no extra dependency needed for that part)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Phase 4: decodes Market Data Feed V3's binary Protobuf messages.
    // "javalite" runtime (not full protobuf-java) — smaller, Android-friendly,
    // matches the "lite" builtin configured in the protobuf {} block above.
    implementation("com.google.protobuf:protobuf-javalite:4.36.0")

    // Phase 5: on-device storage for live ticks (survives app restarts,
    // feeds Phase 6+ charts). room-ktx adds the suspend-fun DAO support used
    // in LiveTickDao/LiveTickStore.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
