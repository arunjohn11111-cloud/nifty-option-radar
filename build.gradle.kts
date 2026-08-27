// Top-level build file. Individual module build files apply the plugins they need.
plugins {
    id("com.android.application") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // Phase 5: Room's annotation processor (generates the DAO/Database implementations
    // in NiftyRadarDatabase.kt et al.) — kapt rather than KSP so its version always
    // matches the Kotlin Gradle plugin version above with nothing extra to pin/verify.
    id("org.jetbrains.kotlin.kapt") version "2.0.21" apply false
    // Phase 4: compiles app/src/main/proto/MarketDataFeed.proto into Kotlin
    // classes for decoding Market Data Feed V3 WebSocket messages.
    id("com.google.protobuf") version "0.10.0" apply false
}
