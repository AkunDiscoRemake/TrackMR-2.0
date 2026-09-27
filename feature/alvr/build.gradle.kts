plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.trackmr.alvr"
    defaultConfig {
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    // Optional: CI drops libalvr_client_core.so here (MIT, built from ALVR sources).
    sourceSets["main"].jniLibs.srcDir(rootProject.file("third_party/alvr/jniLibs"))
}
dependencies { api(project(":core:ui")) }
