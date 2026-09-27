plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.trackmr.xr" }
dependencies {
    api("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
}
