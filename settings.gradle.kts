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
rootProject.name = "Nota"
include(":app")
// Phase 2.7: the legacy fingerprint bridge module. It compiles
// against API 36 because compileSdk 37 removed the framework
// FingerprintManager stubs from the SDK; see
// legacyfingerprint/build.gradle.kts.
include(":legacyfingerprint")
