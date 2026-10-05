plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "1.1.13"
}

group = "uy.ct"
version = "0.18.1"
description = "shortener"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

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
        "deploy/k8s/base/kustomization.yaml",
        "deploy/k8s/base/deployment.yaml",
        "deploy/k8s/overlays/production/kustomization.yaml",
        "deploy/keycloak/DpopClient.java",
        "deploy/keycloak/dev-keys/demo-client.jwk.json",
        "deploy/postgres/bootstrap.sql",
    )
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
