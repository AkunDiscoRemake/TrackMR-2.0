plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.trackmr.ui" }
dependencies {
    api(project(":core:xr"))
}
