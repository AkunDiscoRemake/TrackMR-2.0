plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "dev.trackmr"
    compileSdk = 35
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "dev.trackmr"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "2.0.0-alpha04"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_shared" } }
    }
    buildFeatures { aidl = true; buildConfig = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
    androidResources { noCompress += listOf("task", "tflite", "bin") }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation(project(":cardboard"))
    implementation(project(":core"))
    implementation(project(":xr"))
    implementation(project(":openxr"))
    implementation(project(":handtracking"))
    implementation(project(":dev-api"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.ar:core:1.48.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
