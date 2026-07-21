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
        // TarsosDSP (피치 검출) — Phase 2에서 사용. Maven Central 아님.
        maven { url = uri("https://mvn.0110.be/releases") }
    }
}
rootProject.name = "BYD Karaoke"
include(":app")
