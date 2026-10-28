// Flutter plugin Android — cầu mỏng sang SDK native (06-sdk-flutter.md §6.4).
//
// Plugin áp `com.android.library` + `kotlin.android` KHÔNG kèm version: version do
// build.gradle.kts của app/example khai báo (`plugins { id(...) version ... apply false }`).
//
// SDK native nằm NGOÀI cây Flutter (v3nano-mobile/android/sdk + sdk-engine-ort) nên
// app/example phải include 2 module đó qua `projectDir` trong settings.gradle.kts —
// xem example/android/settings.gradle.kts.
group = "com.vieneu.mobile.flutter"
version = "0.1.0-dev.1"

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vieneu.mobile.flutter"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    implementation(project(":vieneu-mobile-sdk"))
    implementation(project(":sdk-engine-ort"))
    implementation(project(":sdk-g2p"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
