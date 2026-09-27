plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.trackmr.browser" }
dependencies { api(project(":core:ui")) }
