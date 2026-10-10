plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.2.0-M2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "1.1.14" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.sonarqube") version "7.5.0.8588"
}

group = "uy.ct"
version = "0.21.1"
description = "shortener"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

/**
 * The GraalVM plugin is applied only with `-Pnative`, for `perf/builds/native.sh`: it marks the jar
 * `Spring-Boot-Native-Processed`, which the `spring-boot` buildpack reads as a request for a native image.
 */
val native = providers.gradleProperty("native").isPresent
if (native) apply(plugin = "org.graalvm.buildtools.native")

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
    testImplementation("com.tngtech.archunit:archunit:1.5.1")
    testImplementation("com.nimbusds:nimbus-jose-jwt:10.9.1") // the tests import it; not in the Boot BOM, and the version Spring Security resolves
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
 * Coverage report, with no threshold: `./gradlew test koverHtmlReport koverXmlReport` writes build/reports/kover. CI keeps it as
 * an artifact.
 */
kover {
    currentProject {
        instrumentation {
            // Only the classes of the project.
            includedClasses.add("uy.ct.shortener.*")
        }
    }
    reports {
        filters {
            excludes {
                // Generated ahead-of-time and native classes, and `main`.
                classes("*__*", "*\$\$*", "uy.ct.shortener.ShortenerApplicationKt")
            }
        }
    }
}

/**
 * Analysis and coverage on SonarCloud, from CI: `./gradlew sonar -x test`, with SONAR_TOKEN in the environment.
 *
 * - Coverage: the XML report of Kover, which has the format of JaCoCo, so run `koverXmlReport` first.
 * - Scope: the application. The tools are a build of their own and are not analysed.
 * - Needs Automatic Analysis switched off on the project, because the two cannot both feed it.
 */
sonar {
    properties {
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.organization", "christ008")
        property("sonar.projectKey", "christ008_shortener")
        property("sonar.coverage.jacoco.xmlReportPaths", layout.buildDirectory.file("reports/kover/report.xml").get().asFile.path)
    }
}

/**
 * Mutation testing of the logic of the `shortlink` module, by hand: `./gradlew mutationTest`. Not part of `check` and not in CI.
 * Results: the Testing section of docs/INTERNALS.md.
 *
 * - Scope: the domain types, the service, the redirect cache, the target-URL policy and the code generator.
 * - Tests: the ones that need no Docker.
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
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xjspecify-annotations=strict")
        javaParameters = true
        allWarningsAsErrors = true
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("project.version", project.version.toString())
    inputs.files(
        "deploy/stack/compose.prod.yaml",
        "deploy/stack/overlays/compose.observability.yaml",
        "deploy/stack/overlays/compose.keycloak.yaml",
        "deploy/stack/overlays/compose.postgres-ha.yaml",
        "deploy/edge/keycloak.conf",
        "deploy/stack/.env.example",
        "deploy/keycloak/DpopClient.java",
        "deploy/postgres/bootstrap.sql",
    )
}

// `./gradlew test` also runs the tests of the tools (settings.gradle.kts).
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

if (native) {
    tasks.named<org.springframework.boot.gradle.tasks.aot.ProcessAot>("processAot") {
        args("--spring.profiles.active=production")
    }
}

/**
 * A JVM image from buildpacks (ADR 0036). The builder and the run image are pinned by digest, which pins the buildpacks the
 * builder carries; `health-checker` adds `/workspace/health-check`. `syft` 2.41.0 replaces the builder's 2.42.1, which
 * downloads an amd64 binary on arm64 (paketo-buildpacks/syft#479): drop that line when it is fixed. The flags are explained
 * in INTERNALS.md#image.
 */
tasks.bootBuildImage {
    imageName = "shortener:${project.version}"
    builder = "bellsoft/buildpacks.builder:glibc@sha256:c7a8d5fcc863a7e5c36a6691d7ccd174cd4acd701a7338867e1bb7a61389de92"
    runImage = "bellsoft/buildpacks.hardened-run:glibc@sha256:81f455eb612bb818b37d3c02087fde31cd07a776334ed686f98906448b2eff42"
    buildpacks = listOf(
        "urn:cnb:builder:bellsoft/buildpacks/liberica",
        "docker://paketobuildpacks/syft:2.41.0",
        "urn:cnb:builder:paketo-buildpacks/executable-jar",
        "urn:cnb:builder:paketo-buildpacks/spring-boot",
        "urn:cnb:builder:paketo-buildpacks/environment-variables",
        "docker://paketobuildpacks/health-checker:2.14.0",
    )
    environment = mapOf(
        "BP_HEALTH_CHECKER_ENABLED" to "true",
        "BP_SPRING_CLOUD_BINDINGS_DISABLED" to "true",
        "BPE_DELIM_JAVA_TOOL_OPTIONS" to " ",
        "BPE_APPEND_JAVA_TOOL_OPTIONS" to "-XX:+UseG1GC -XX:+UseCompactObjectHeaders -XX:ReservedCodeCacheSize=64M",
        "BPE_OVERRIDE_BPL_JVM_THREAD_COUNT" to "50",
        "BPE_DEFAULT_BPL_JAVA_NMT_ENABLED" to "false",
        "BPE_DEFAULT_SPRING_PROFILES_ACTIVE" to "production",
    )
}
