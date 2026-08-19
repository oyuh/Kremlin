plugins {
    java
}

group = "me.lawsonhart"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("dev.folia:folia-api:26.2.build.4-beta")
    // The ported CombatPrev tests exercise Bukkit's YamlConfiguration, so the API is on the
    // test classpath for real rather than compile-only.
    testImplementation("dev.folia:folia-api:26.2.build.4-beta")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// Self-check for the MiniMessage build and the EssentialsX hand-off: ./gradlew nickCheck
tasks.register<JavaExec>("nickCheck") {
    // folia-api is compileOnly, so it (and Adventure with it) is only on the compile classpath.
    classpath = sourceSets.main.get().output + configurations.compileClasspath.get()
    mainClass = "me.lawsonhart.kremlin.NickCheck"
}
