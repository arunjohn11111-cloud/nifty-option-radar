plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
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

    debugImplementation("androidx.compose.ui:ui-tooling")
}
