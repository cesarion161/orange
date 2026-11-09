plugins {
    // Lets Gradle download the toolchain JDK (see java.toolchain in build.gradle.kts)
    // on machines that don't have it installed, e.g. a fresh VPS.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "orange"
