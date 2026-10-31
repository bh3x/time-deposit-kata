rootProject.name = "time-deposit-kata"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    // All repositories are declared here, none in build scripts (ADR-0007: no custom build logic).
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
}
