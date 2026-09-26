plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies { implementation(project(":core")); implementation(project(":xr")); testImplementation("junit:junit:4.13.2") }
