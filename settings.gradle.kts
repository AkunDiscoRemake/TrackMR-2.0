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

rootProject.name = "TrackMR-2.0"

// TrackMR Core — see docs/ARCHITECTURE.md for the module map.
val trackmrModules = listOf(
    ":app",
    // XR Core and platform layers
    ":core:xr",
    ":core:openxr",
    ":core:arcore",
    ":core:hands",
    ":core:depth",
    ":core:mr",
    ":core:vr",
    ":core:ui",
    // Platform features (all spatial, no 2D UI)
    ":feature:androidwindows",
    ":feature:browser",
    ":feature:cinema",
    ":feature:environments",
    ":feature:emulation",
    ":feature:alvr",
    ":feature:gamelibrary",
    // Game runtime
    ":runtime:lua",
    ":runtime:game",
)

// Modules are included when their sources exist, so partial checkouts still build.
trackmrModules.filter { it == ":app" || file(it.removePrefix(":").replace(':', '/') + "/src/main").exists() }
    .forEach { include(it) }
