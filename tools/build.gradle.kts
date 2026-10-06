/**
 * The tools of the repository: dev-setup, the realm maker, the smoke test and the report reader (docs/adr/0029-tools-in-kotlin.md).
 *
 * - A build of its own, not a module of the application's, so that running a tool does not configure the application, whose
 *   toolchain asks for JDK 25. The tools need a JDK 17 or newer, the baseline of the DPoP client.
 * - `tools/run` builds them when a source is newer than the last build and starts them, so nobody needs to know this exists.
 *   Tests: `./gradlew -p tools test`.
 * - `DpopClient.java` of deploy/keycloak is compiled with them, as it is, for 17, so that the smoke test calls the one client
 *   there is instead of a copy of it.
 * - Versions follow the application's: Kotlin as in ../build.gradle.kts, the rest from the Spring Boot BOM of the same version.
 */
plugins {
    kotlin("jvm") version "2.3.21"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    implementation("tools.jackson.core:jackson-databind")
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.assertj:assertj-core")
    testImplementation("com.nimbusds:nimbus-jose-jwt:10.9.1") // not in the Boot BOM: the version the application resolves, through Spring Security
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        // Only what a JDK 17 has, so that a call that exists from 22 on (Console.isTerminal) fails here and not on a laptop.
        freeCompilerArgs.add("-Xjdk-release=17")
        allWarningsAsErrors = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
    options.release = 17
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

sourceSets.main {
    java.srcDir("../deploy/keycloak")
    java.include("DpopClient.java")
}

application {
    mainClass = "uy.ct.shortener.tools.MainKt"
}

tasks.test {
    useJUnitPlatform()
    // The tools work from the root of the repository, which tests read their fixtures from.
    systemProperty("repository.root", rootDir.parentFile.absolutePath)
}
