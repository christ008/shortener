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
    implementation("org.springframework.modulith:spring-modulith-starter-core")
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
 * The native image is built on BellSoft's Alpaquita (musl) builder, and every part of the build is pinned by
 * digest so that a rebuild of the same commit produces the same image. The tag in each reference says which
 * version the digest is, and the digest is what is used.
 *
 * - Builder and run image: `bellsoft/buildpacks.builder:musl` and `bellsoft/buildpacks.hardened-run:musl`.
 * - Paketo buildpacks: listed in detection order, replacing the builder's default order. Liberica, the JDK and
 *   native image kit, comes from the builder itself.
 * - Health check: the `health-checker` buildpack adds `/workspace/health-check` (Tiny Health Checker), which
 *   the container healthcheck runs with `THC_PORT` and `THC_PATH` set.
 *
 * To update, look up the new digest of each tag (`docker buildx imagetools inspect <image>:<tag>`).
 */
tasks.bootBuildImage {
    val profiling = providers.gradleProperty("nativeProfiling").isPresent
    imageName = "shortener:${project.version}${if (profiling) "-profiling" else ""}"
    builder = "bellsoft/buildpacks.builder:musl@sha256:11a4d7b224b5950fe77b7cccb7b03c182faefd7c05ccc3d12a125be24c8554da"
    runImage = "bellsoft/buildpacks.hardened-run:musl@sha256:c4ad07072db55ea775e5b9364ab2899ef54688a260523bf8b52aa7367772ba91"
    buildpacks = listOf(
        "urn:cnb:builder:bellsoft/buildpacks/liberica",
        "docker://paketobuildpacks/syft:2.42.1@sha256:22db5d0b9414330405e025b1df8fa6fcb285e52406d951224abfe97e2c2c7639",
        "docker://paketobuildpacks/executable-jar:6.17.1@sha256:a5c40dea1295fc445c8b081c0e2997949487233af204a08f95ac15df674840e8",
        "docker://paketobuildpacks/spring-boot:5.39.0@sha256:3efe7ab8799622a4ac2023d963d730d256fd97e9a7029a7e7dee572c14428676",
        "docker://paketobuildpacks/native-image:5.19.0@sha256:e6af82918f940347bc026daef787161b98cabac937c0902a27fdd160f9b47f80",
        "docker://paketobuildpacks/health-checker:2.14.0@sha256:79ee91f6e38f441a2f7f346300f48f2aef9cd1ef775db985b8b5e0bb5a83f164",
    )
    environment = mapOf(
        "BP_NATIVE_IMAGE_BUILD_ARGUMENTS" to "-march=compatibility -J-Xmx7g${if (profiling) " --enable-monitoring=jfr,heapdump" else ""}",
        "BP_OCI_VERSION" to project.version.toString(),
        "BP_HEALTH_CHECKER_ENABLED" to "true",
    )
}
