plugins {
    // Lets the Java 25 toolchain (ADR-0007) be downloaded when no local JDK 25 exists. Settings plugins cannot use
    // the version catalog, so its version is pinned here.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "time-deposit-kata"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // All repositories are declared here, none in build scripts (ADR-0007: no custom build logic).
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
}
