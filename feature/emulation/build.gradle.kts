plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android { namespace = "com.trackmr.emulation" }
dependencies {
    api(project(":core:ui"))
    api(project(":feature:androidwindows"))
    testImplementation("junit:junit:4.13.2")
}
