plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "1.1.13"
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "uy.ct"
version = "0.21.0"
description = "shortener"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

apply(from = "gradle/tooling.gradle.kts")

extra["springModulithVersion"] = "2.1.1"
extra["testcontainersVersion"] = "2.0.5"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-data-commons")
    implementation("org.springframework.data:spring-data-commons")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
    implementation("com.github.ben-manes.caffeine:caffeine")
    testImplementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("tools.jackson.module:jackson-module-kotlin")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
    testImplementation("io.micrometer:micrometer-observation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("com.tngtech.archunit:archunit:1.4.2")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}")
    }
}

/**
 * Coverage, as a measurement and not a gate: `./gradlew koverHtmlReport koverXmlReport` after `./gradlew test` writes
 * build/reports/kover, and CI keeps it as an artifact. Only the whole suite says what the project covers, and part of it needs
 * Docker.
 */
kover {
    currentProject {
        instrumentation {
            // Only the classes of the project: the rest are loaded by the tests too, and instrumenting them only fills a log.
            includedClasses.add("uy.ct.shortener.*")
        }
    }
    reports {
        filters {
            excludes {
                // The classes that ahead-of-time and native builds generate, and `main`, which no test starts: not what the tests are about.
                classes("*__*", "*\$\$*", "uy.ct.shortener.ShortenerApplicationKt")
            }
        }
    }
}

/**
 * Mutation testing of the logic of the `shortlink` module, run by hand with `./gradlew mutationTest` (not part of `check`, and not
 * in CI): it changes the code in small ways and runs the tests that cover each change, and a change that no test notices is a
 * behaviour that nothing checks. See the Testing section of docs/INTERNALS.md for what it found.
 *
 * - Scope: the domain types, the service, the redirect cache, the target-URL policy and the code generator. Not the web and
 *   persistence adapters (tested against HTTP and Postgres, not through mutation), not authorization (a framework's expressions),
 *   not the audit trail, and not configuration, properties and native hints.
 * - Tests: the ones that need no Docker, because it demands a green run before it mutates anything. Mutants that only a test
 *   with a database would kill are reported as surviving, and read as such.
 */
pitest {
    junit5PluginVersion = "1.2.3"
    pitestVersion = "1.30.0"
    targetClasses = setOf(
        "uy.ct.shortener.shortlink.Actor*",
        "uy.ct.shortener.shortlink.CreatedByFilter*",
        "uy.ct.shortener.shortlink.InsertResult*",
        "uy.ct.shortener.shortlink.LinkLookup*",
        "uy.ct.shortener.shortlink.LinkStatus*",
        "uy.ct.shortener.shortlink.ShortCode",
        "uy.ct.shortener.shortlink.ShortCode\$*",
        "uy.ct.shortener.shortlink.ShortLink",
        "uy.ct.shortener.shortlink.ShortLink\$*",
        "uy.ct.shortener.shortlink.internal.DefaultShortLinkService*",
        "uy.ct.shortener.shortlink.internal.CaffeineRedirectCache*",
        "uy.ct.shortener.shortlink.internal.RedirectCache",
        "uy.ct.shortener.shortlink.internal.NoRedirectCache*",
        "uy.ct.shortener.shortlink.internal.TargetUrlPolicy",
        "uy.ct.shortener.shortlink.internal.AnyTarget*",
        "uy.ct.shortener.shortlink.internal.AllowedHosts*",
        "uy.ct.shortener.shortlink.internal.RandomShortCodeGenerator*",
    )
    targetTests = setOf(
        "uy.ct.shortener.shortlink.ShortCodeTest",
        "uy.ct.shortener.shortlink.ShortLinkModelTest",
        "uy.ct.shortener.shortlink.internal.DefaultShortLinkServiceTest",
        "uy.ct.shortener.shortlink.internal.RedirectCacheTest",
        "uy.ct.shortener.shortlink.internal.RandomShortCodeGeneratorTest",
        "uy.ct.shortener.shortlink.internal.TargetUrlPolicy*Test",
        "uy.ct.shortener.shortlink.internal.AuditTrailTest",
        "uy.ct.shortener.shortlink.internal.ShortLinkAuthorizationTest",
    )
    threads = 4
    outputFormats = setOf("HTML", "XML")
    timestampedReports = false
    failWhenNoMutations = false
}

tasks.register("mutationTest") {
    group = "verification"
    description = "Mutation testing of the logic of the shortlink module, by hand and not in CI (see the pitest block)."
    dependsOn("pitest")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjspecify-annotations=strict", "-Xannotation-default-target=param-property")
        javaParameters = true
        allWarningsAsErrors = true
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("project.version", project.version.toString())
    inputs.files(
        "compose.prod.yaml",
        "compose.prod.observability.yaml",
        "deploy/stack/.env.example",
        "deploy/keycloak/DpopClient.java",
        "deploy/postgres/bootstrap.sql",
    )
}

// `./gradlew test` tests the tools too, which are a build of their own (settings.gradle.kts): CI and a release run only that.
tasks.test {
    dependsOn(gradle.includedBuild("tools").task(":test"))
}

tasks.bootRun {
    if (System.getenv("SPRING_PROFILES_ACTIVE") == null) systemProperty("spring.profiles.active", "dev")
    doFirst {
        if (!file("deploy/keycloak/shortener-realm.json").exists()) {
            throw GradleException("The dev keys and passwords do not exist yet. Run deploy/keycloak/dev-setup first.")
        }
    }
}

tasks.named<org.springframework.boot.gradle.tasks.aot.ProcessAot>("processAot") {
    args("--spring.profiles.active=production")
}

/**
 * The native image is built on BellSoft's Alpaquita (musl) builder, with the build pinned so that a rebuild of the
 * same commit produces the same image.
 *
 * - Paketo buildpacks are pinned by version, listed in detection order, which replaces the builder's default order.
 *   Liberica, the JDK and native image kit, comes from the builder itself.
 * - BellSoft publishes only the rolling tags `musl` and `glibc` for its builder and run image, so those two are pinned
 *   by digest, the only fixed reference they have.
 * - The `health-checker` buildpack adds `/workspace/health-check` (Tiny Health Checker), which the container
 *   healthcheck runs with `THC_PORT` and `THC_PATH` set.
 * - `-Os` optimizes the native image for size: a binary a fifth smaller for about 10% more CPU per request.
 *
 * To update, bump the version in each reference, or look up a new digest with
 * `docker buildx imagetools inspect <image>:<tag>`.
 */
tasks.bootBuildImage {
    val profiling = providers.gradleProperty("nativeProfiling").isPresent
    imageName = "shortener:${project.version}${if (profiling) "-profiling" else ""}"
    builder = "bellsoft/buildpacks.builder:musl@sha256:11a4d7b224b5950fe77b7cccb7b03c182faefd7c05ccc3d12a125be24c8554da"
    runImage = "bellsoft/buildpacks.hardened-run:musl@sha256:c4ad07072db55ea775e5b9364ab2899ef54688a260523bf8b52aa7367772ba91"
    buildpacks = listOf(
        "urn:cnb:builder:bellsoft/buildpacks/liberica",
        "docker://paketobuildpacks/syft:2.42.1",
        "docker://paketobuildpacks/executable-jar:6.17.1",
        "docker://paketobuildpacks/spring-boot:5.39.0",
        "docker://paketobuildpacks/native-image:5.19.0",
        "docker://paketobuildpacks/health-checker:2.14.0",
    )
    environment = mapOf(
        "BP_NATIVE_IMAGE_BUILD_ARGUMENTS" to "-march=compatibility -Os -J-Xmx7g${if (profiling) " --enable-monitoring=jfr,heapdump" else ""}",
        "BP_OCI_VERSION" to project.version.toString(),
        "BP_HEALTH_CHECKER_ENABLED" to "true",
    )
}
