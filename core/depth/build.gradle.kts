plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.trackmr.depth" }
dependencies {
    api(project(":core:xr"))
}
