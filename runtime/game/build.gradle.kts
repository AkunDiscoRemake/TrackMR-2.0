plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.trackmr.game"
    // Optional asset packs produced by the asset pipeline (Sketchfab CC models, etc.).
    sourceSets["main"].assets.srcDir(rootProject.file("app/src/generated/gamepacks"))
}
dependencies {
    api(project(":core:ui"))
    api(project(":core:depth"))
    api(project(":runtime:lua"))
    testImplementation("junit:junit:4.13.2")
}
