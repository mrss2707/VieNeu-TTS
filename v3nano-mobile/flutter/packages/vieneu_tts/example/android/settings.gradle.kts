// Example app settings — KHÔNG sinh tự động bởi `flutter create`: app/example phải
// trỏ tường minh về 2 module SDK native nằm ngoài cây Flutter.
//
// LƯU Ý: `flutter create .` mặc định sinh `settings.gradle` (Groovy). Nếu file Groovy
// tồn tại, Gradle sẽ dùng nó và BỎ QUA file .kts này — khi đó hãy copy 2 block
// `include(":vieneu-mobile-sdk")` / `include(":sdk-engine-ort")` bên dưới vào file Groovy.
pluginManagement {
    val flutterSdkPath: String = run {
        val properties = java.util.Properties()
        file("local.properties").inputStream().use { properties.load(it) }
        properties.getProperty("flutter.sdk")
            ?: error("flutter.sdk not set in local.properties")
    }
    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

include(":app")

// ── SDK native (ngoài cây Flutter): android/sdk (core JVM) + android/sdk-engine-ort ──
// 5 cấp: example/android -> example -> vieneu_tts -> packages -> flutter -> v3nano-mobile.
include(":vieneu-mobile-sdk")
project(":vieneu-mobile-sdk").projectDir = file("../../../../../android/sdk")
include(":sdk-engine-ort")
project(":sdk-engine-ort").projectDir = file("../../../../../android/sdk-engine-ort")
// G2P on-device (sea-g2p Rust qua JNI + từ điển vi.seaz). Dựng bằng native/sea-g2p-mobile/build_android.sh.
include(":sdk-g2p")
project(":sdk-g2p").projectDir = file("../../../../../android/sdk-g2p")
