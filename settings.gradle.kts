pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "TrackMR-2.0"
include(":app", ":core", ":dev-api", ":runtime", ":cardboard")
include(":xr", ":handtracking")
include(":openxr")
