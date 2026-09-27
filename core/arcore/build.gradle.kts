plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.trackmr.arcore" }
dependencies {
    api(project(":core:xr"))
    api("com.google.ar:core:1.48.0")
}
