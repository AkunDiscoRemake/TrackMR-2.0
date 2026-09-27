plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.trackmr.androidwindows" }
dependencies {
    api(project(":core:ui"))
    api("dev.rikka.shizuku:api:13.1.5")
    api("dev.rikka.shizuku:provider:13.1.5")
    // Apache-2.0: allows reflective access to hidden input APIs used with Shizuku.
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
