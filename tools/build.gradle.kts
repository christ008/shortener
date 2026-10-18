import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The tools of the repository: dev-setup, the realm maker, the smoke test and the report reader (docs/adr/0029-tools-in-kotlin.md).
 *
 * - A build of its own, not a module of the application's. Needs a JDK 25, the application's (`Dataset` uses stream gatherers, final in 24).
 * - `tools/run` builds and starts them. Tests: `./gradlew -p tools test`.
 * - They do not use `deploy/keycloak/DpopClient.java`, the reference client: `ClientKeys` and `DpopCalls` do what they need of it.
 * - Versions follow the application's: Kotlin as in ../build.gradle.kts, the rest from the Spring Boot BOM of the same version.
 */
plugins {
    kotlin("jvm") version "2.4.20"
    application
}

repositories {
    mavenCentral()
}

extra["nimbusVersion"] = "10.10"

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.2.0-M2"))
    implementation("tools.jackson.core:jackson-databind")
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.2.0-M2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.assertj:assertj-core")
    testImplementation("com.nimbusds:nimbus-jose-jwt:${property("nimbusVersion")}") // not in the Boot BOM: the version the application resolves, through Spring Security
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        // Compile against the JDK 25 API only.
        freeCompilerArgs.add("-Xjdk-release=25")
        allWarningsAsErrors = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

application {
    mainClass = "uy.ct.shortener.tools.MainKt"
}

tasks.test {
    useJUnitPlatform()
    // Tests read their fixtures from the root of the repository.
    systemProperty("repository.root", rootDir.parentFile.absolutePath)
}

// The reference client of the Kotlin side as one executable JAR, with its dependencies: `java -jar build/libs/dpop.jar`.
// It runs on JDK 25, the same baseline as deploy/keycloak/DpopClient.java.
val dpopJar by tasks.registering(Jar::class) {
    group = "distribution"
    description = "Packages the Kotlin DPoP client as an executable JAR, java -jar build/libs/dpop.jar."
    archiveFileName.set("dpop.jar")
    manifest { attributes("Main-Class" to "uy.ct.shortener.tools.dpop.DpopClientKt") }
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { files -> files.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
