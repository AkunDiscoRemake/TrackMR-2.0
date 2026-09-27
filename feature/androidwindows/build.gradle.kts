plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.trackmr.androidwindows"
    buildFeatures { aidl = true }
}
dependencies {
    api(project(":core:ui"))
    api("dev.rikka.shizuku:api:13.1.5")
    api("dev.rikka.shizuku:provider:13.1.5")
}
