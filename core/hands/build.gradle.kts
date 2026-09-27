import java.io.InputStream
import java.net.URI

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.trackmr.hands"
    defaultConfig {
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    androidResources { noCompress += listOf("task", "tflite") }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/handModel"))
}

// Downloads the Apache-2.0 MediaPipe Hand Landmarker bundle at build time (never committed).
val handModelUrl = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
val fetchHandModel by tasks.registering {
    val out = layout.buildDirectory.file("generated/handModel/models/hand_landmarker.task")
    outputs.file(out)
    doLast {
        val f = out.get().asFile
        if (f.exists() && f.length() > 1_000_000) return@doLast
        f.parentFile.mkdirs()
        val cached = rootProject.file(".cache/models/hand_landmarker.task")
        if (cached.exists() && cached.length() > 1_000_000) { cached.copyTo(f, overwrite = true); return@doLast }
        try {
            URI(handModelUrl).toURL().openStream().use { input: InputStream -> f.outputStream().use { out -> input.copyTo(out) } }
            logger.lifecycle("TrackMR: hand_landmarker.task downloaded (${f.length()} bytes)")
        } catch (e: Exception) {
            logger.warn("TrackMR: could not download hand model (${e.message}); hand tracking will be disabled at runtime")
        }
    }
}
tasks.named("preBuild") { dependsOn(fetchHandModel) }

dependencies {
    api(project(":core:xr"))
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    testImplementation("junit:junit:4.13.2")
}
