plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.trackmr.environments"
    // The 360° environments already in the repository root are packaged from their original
    // location (never moved/replaced). CI may place optimized WebP variants in build/generated.
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/envAssets"))
}
val packageRepoEnvironments by tasks.registering(Copy::class) {
    val optimized = rootProject.file("app/src/generated/environments")
    if (optimized.exists()) {
        from(optimized)
    } else {
        from(rootProject.file("cf311f28-9e2f-4346-9afb-de1b963dfc40.png")) { rename { "penthouse_sunset.png" } }
        from(rootProject.file("file_0000000046cc820ebadab3742a546cd2.png")) { rename { "neon_street_night.png" } }
    }
    into(layout.buildDirectory.dir("generated/envAssets/environments"))
}
tasks.named("preBuild") { dependsOn(packageRepoEnvironments) }
dependencies {
    api(project(":core:ui"))
}
