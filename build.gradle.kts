import com.android.build.gradle.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}

// Shared configuration for every TrackMR Android library module.
val trackmrCompileSdk = 35
val trackmrMinSdk = 26
val trackmrNdk = "27.2.12479018"
extra["trackmrNdk"] = trackmrNdk

subprojects {
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension> {
            compileSdk = trackmrCompileSdk
            ndkVersion = trackmrNdk
            defaultConfig {
                minSdk = trackmrMinSdk
                consumerProguardFiles("consumer-rules.pro")
                ndk { abiFilters += listOf("arm64-v8a") }
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
            lint { abortOnError = false }
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
}
