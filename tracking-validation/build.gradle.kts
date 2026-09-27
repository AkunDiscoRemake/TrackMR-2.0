plugins { id("com.android.application"); kotlin("android") }
android {
    namespace="dev.trackmr.validation"
    compileSdk=35
    defaultConfig {
        applicationId="dev.trackmr.validation"
        minSdk=29;targetSdk=35
        testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    sourceSets["main"].assets.srcDir("../app/src/main/assets")
    androidResources { noCompress += "task" }
}
// Compile the EXACT production tracker, not a mock or copy. No launcher, renderer or camera HAL test.
kotlin.sourceSets["main"].kotlin.apply {
    srcDir("../app/src/main/java")
    include("dev/trackmr/tracking/HandTracker.kt", "dev/trackmr/tracking/HandInputImage.kt",
        "dev/trackmr/camera/CameraFeed.kt", "dev/trackmr/camera/CameraPipelineState.kt")
}
dependencies {
    implementation(project(":handtracking"))
    // Same dependency resolution as the shipped app, including GenAI's transitive tasks-core.
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    implementation("com.google.mediapipe:tasks-genai:0.10.24")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
