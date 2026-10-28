plugins {
    id("com.android.library") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

android {
    namespace = "com.vieneu.mobile.g2p"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // Chỉ arm64: khớp supported_abi của gói model (arm64-v8a).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // g2p_golden.json (sinh bởi host/export_g2p_golden.py) dùng chung cho test JVM và test trên máy.
    sourceSets["androidTest"].resources.srcDir("src/test/resources")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // vi.seaz đã nén zstd theo khối — Store không nén thêm được, và không nén trong APK
    // thì chép ra filesDir bằng stream thẳng, không giải nén lần hai.
    androidResources { noCompress += "seaz" }

    testOptions {
        unitTests.all { test ->
            // Parity JVM chạy lõi Rust thật (dylib macOS build bởi native/sea-g2p-mobile/build_android.sh).
            test.systemProperty("java.library.path", file("../../native/sea-g2p-mobile/out/host").absolutePath)
            test.systemProperty("sea.g2p.dict", file("../../native/sea-g2p-mobile/out/host/vi.seaz").absolutePath)
        }
    }
}

// Lõi Rust (sea-g2p qua JNI) + từ điển SEAZ. :vieneu-mobile-sdk giữ thuần JVM, chỉ biết
// interface G2pBackend; module này cắm bản native vào.
dependencies {
    api(project(":vieneu-mobile-sdk"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
