plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.trackmr.lua"
    defaultConfig {
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies { }
