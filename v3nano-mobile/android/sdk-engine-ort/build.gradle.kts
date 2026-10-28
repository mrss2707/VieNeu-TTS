plugins {
    id("com.android.library") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

android {
    namespace = "com.vieneu.mobile.ort"
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

// Module ORT tách riêng để :vieneu-mobile-sdk (core thuần JVM) KHÔNG kéo theo
// onnxruntime-android — test JVM của core vẫn chạy không cần emulator.
// `api` để app tiêu thụ chỉ cần phụ thuộc module này là có cả SDK core + ORT.
dependencies {
    api(project(":vieneu-mobile-sdk"))
    api("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}
