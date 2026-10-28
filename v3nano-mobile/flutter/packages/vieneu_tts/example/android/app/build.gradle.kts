plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.vieneu_tts_example"
    compileSdk = flutter.compileSdkVersion
    // KHÔNG set ndkVersion: app + plugin đều thuần Kotlin/Java (ORT ship .so dựng sẵn),
    // nên không cần NDK — bỏ dòng này tránh AGP tải NDK ~1GB.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.vieneu_tts_example"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // SDK native (:vieneu-mobile-sdk / :sdk-engine-ort) yêu cầu minSdk 26;
        // Flutter mặc định 24 → manifest merger sẽ fail nếu không nâng.
        minSdk = 26
        targetSdk = flutter.targetSdkVersion
        // Uses the version code from pubspec.yaml. When using split APKs, 1000 * ABI_VERSION
        // is added automatically by Flutter. (https://developer.android.com/studio/build/configure-apk-splits#configure-APK-versions)
        // You can force using the value of versionCode by specifying the `-P force-version-code-ignoring-abi=true`
        // flag during build.
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        // Chỉ arm64 (khớp supported_abi của gói model + libsea_g2p_rs chỉ dựng arm64-v8a).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // vi.seaz đã nén zstd theo khối: không nén lại trong APK để chép ra filesDir bằng stream thẳng.
    androidResources { noCompress += "seaz" }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}
