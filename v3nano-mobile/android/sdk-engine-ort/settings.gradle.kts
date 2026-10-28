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

rootProject.name = "vieneu-mobile-sdk-engine-ort"
// SDK core (thuần JVM) nằm ngoài cây module này — include qua projectDir TƯỜNG MINH,
// cùng pattern với android/device-validation/settings.gradle.kts.
include(":vieneu-mobile-sdk")
project(":vieneu-mobile-sdk").projectDir = file("../sdk")
