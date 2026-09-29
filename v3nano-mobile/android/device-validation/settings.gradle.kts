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

rootProject.name = "device-validation"
include(":app")
// SDK sản phẩm (core thuần JVM, 04-sdk-android.md §4.1) — include qua projectDir TƯỜNG
// MINH vì module nằm ngoài cây device-validation (android/sdk/). Project SDK vẫn chạy
// standalone bằng settings.gradle.kts của chính nó.
include(":vieneu-mobile-sdk")
project(":vieneu-mobile-sdk").projectDir = file("../sdk")
