plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.trackmr.cinema" }
dependencies {
    api(project(":core:ui"))
    implementation(project(":core:mr"))
    implementation(project(":feature:environments"))
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
}
