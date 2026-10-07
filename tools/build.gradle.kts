/**
 * The tools of the repository: dev-setup, the realm maker, the smoke test and the report reader (docs/adr/0029-tools-in-kotlin.md).
 *
 * - A build of its own, not a module of the application's. Needs a JDK 17 or newer.
 * - `tools/run` builds and starts them. Tests: `./gradlew -p tools test`.
 * - They do not use `deploy/keycloak/DpopClient.java`, the reference client: `ClientKeys` and `DpopCalls` do what they need of it.
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
    testImplementation("com.nimbusds:nimbus-jose-jwt:10.10") // not in the Boot BOM: the version the application resolves, through Spring Security
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        // Compile against the JDK 17 API only.
        freeCompilerArgs.add("-Xjdk-release=17")
        allWarningsAsErrors = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass = "uy.ct.shortener.tools.MainKt"
}

tasks.test {
    useJUnitPlatform()
    // Tests read their fixtures from the root of the repository.
    systemProperty("repository.root", rootDir.parentFile.absolutePath)
}
