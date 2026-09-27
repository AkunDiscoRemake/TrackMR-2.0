import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing is configurable through environment variables / GitHub secrets:
//   TRACKMR_KEYSTORE (path), TRACKMR_KEYSTORE_PASSWORD, TRACKMR_KEY_ALIAS, TRACKMR_KEY_PASSWORD
// Without them, release builds are signed with the debug key so the APK is still installable.
val keystorePath: String? = System.getenv("TRACKMR_KEYSTORE")?.takeIf { it.isNotBlank() && file(it).exists() }
val versionProps = Properties().apply {
    val f = rootProject.file("version.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.trackmr.app"
    compileSdk = 35
    ndkVersion = rootProject.extra["trackmrNdk"] as String

    defaultConfig {
        applicationId = "com.trackmr.platform"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("TRACKMR_VERSION_CODE") ?: versionProps.getProperty("versionCode", "1")).toInt()
        versionName = System.getenv("TRACKMR_VERSION_NAME") ?: versionProps.getProperty("versionName", "2.0.0")
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TRACKMR_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TRACKMR_KEY_ALIAS")
                keyPassword = System.getenv("TRACKMR_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
        debug { applicationIdSuffix = ".debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs { useLegacyPackaging = false; pickFirsts += listOf("**/libc++_shared.so") }
    }
    androidResources { noCompress += listOf("task", "tflite", "pack") }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    // Every module that exists in this checkout is linked in (see settings.gradle.kts).
    listOf(
        ":core:xr", ":core:openxr", ":core:arcore", ":core:hands", ":core:depth", ":core:mr", ":core:vr", ":core:ui",
        ":feature:environments", ":feature:androidwindows", ":feature:browser", ":feature:cinema",
        ":feature:emulation", ":feature:alvr", ":feature:gamelibrary", ":runtime:lua", ":runtime:game",
    ).forEach { path -> if (findProject(path) != null) implementation(project(path)) }
}
