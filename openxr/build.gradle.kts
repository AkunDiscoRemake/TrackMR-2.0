plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "dev.trackmr.openxr"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt");version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17;targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("org.khronos.openxr:openxr_loader_for_android:1.1.36") }
