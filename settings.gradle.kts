// Lets Gradle download a JDK 25 toolchain automatically if the machine doesn't have one.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "Kremlin"
