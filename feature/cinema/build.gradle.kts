plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.trackmr.cinema" }
dependencies {
    api(project(":core:ui"))
    implementation("androidx.media3:media3-exoplayer:1.4.1")
}
