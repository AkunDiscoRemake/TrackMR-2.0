plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.trackmr.openxr"
    defaultConfig {
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    buildFeatures { prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies {
    api(project(":core:xr"))
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.36")
}
