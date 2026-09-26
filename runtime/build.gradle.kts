plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.trackmr.runtime"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.trackmr.runtime"
        minSdk = 29; targetSdk = 35
        versionCode = 4; versionName = "2.0.0-alpha04"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    buildFeatures { prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":openxr")); implementation("org.khronos.openxr:openxr_loader_for_android:1.1.36") }
