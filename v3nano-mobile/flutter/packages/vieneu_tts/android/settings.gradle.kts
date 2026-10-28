// Chỉ dùng khi mở RIÊNG module plugin trong Android Studio (standalone).
// Khi build qua example/app, Gradle dùng settings.gradle.kts của app đó — file này
// bị bỏ qua. Đường dẫn 4 cấp: android -> vieneu_tts -> packages -> flutter -> v3nano-mobile.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "vieneu-tts-flutter-plugin"
include(":vieneu-mobile-sdk")
project(":vieneu-mobile-sdk").projectDir = file("../../../../android/sdk")
include(":sdk-engine-ort")
project(":sdk-engine-ort").projectDir = file("../../../../android/sdk-engine-ort")
include(":sdk-g2p")
project(":sdk-g2p").projectDir = file("../../../../android/sdk-g2p")
