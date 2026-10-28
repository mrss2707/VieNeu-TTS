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

rootProject.name = "vieneu-mobile-sdk-g2p"
// SDK core (thuần JVM) nằm ngoài cây module này — include qua projectDir TƯỜNG MINH,
// cùng pattern với android/sdk-engine-ort.
include(":vieneu-mobile-sdk")
project(":vieneu-mobile-sdk").projectDir = file("../sdk")
