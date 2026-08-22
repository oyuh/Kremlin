plugins {
    java
}

group = "me.lawsonhart"

// A tag build names itself after the tag, so pushing v1.2.0 produces Kremlin-1.2.0.jar with a
// plugin.yml that agrees with it. Everything else stays on the number below.
version = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }
    ?.removePrefix("v")
    ?: "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    compileOnly("dev.folia:folia-api:26.2.build.4-beta")
    compileOnly("net.dv8tion:JDA:5.2.1")
    compileOnly("me.clip:placeholderapi:2.11.6")
    // The server fetches JDA itself at runtime (see `libraries:` in plugin.yml), so it is never
    // shaded into the jar -- but the tests that check the Discord command wiring need it here.
    testImplementation("net.dv8tion:JDA:5.2.1")
    // The ported CombatPrev tests exercise Bukkit's YamlConfiguration, so the API is on the
    // test classpath for real rather than compile-only.
    testImplementation("dev.folia:folia-api:26.2.build.4-beta")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// plugin.yml carries ${version} rather than a literal, so the jar and what the server reports
// can never drift apart.
tasks.processResources {
    filesMatching("plugin.yml") { expand("version" to version) }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

// Self-check for the palette and the gradient builder: ./gradlew nickCheck
tasks.register<JavaExec>("nickCheck") {
    // folia-api is compileOnly, so it (and Adventure with it) is only on the compile classpath.
    classpath = sourceSets.main.get().output + configurations.compileClasspath.get()
    mainClass = "me.lawsonhart.kremlin.player.NickCheck"
}
