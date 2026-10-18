import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.springframework.boot.gradle.tasks.aot.ProcessAot

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.2.0-M2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "1.1.14" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.11"
    id("info.solidsoft.pitest") version "1.19.0"
    id("org.sonarqube") version "7.5.0.8588"
    id("com.diffplug.spotless") version "8.10.0"
}

group = "uy.ct"
version = "0.24.2"
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

val ktlintStyle = mapOf(
    "ktlint_code_style" to "intellij_idea",
    "ktlint_standard_function-signature" to "disabled",
    "ktlint_standard_class-signature" to "disabled",
    "ktlint_standard_multiline-expression-wrapping" to "disabled",
    "ktlint_standard_string-template-indent" to "disabled",
    "ktlint_standard_wrapping" to "disabled",
    "ktlint_standard_argument-list-wrapping" to "disabled",
    "ktlint_standard_parameter-list-wrapping" to "disabled",
    "ktlint_standard_statement-wrapping" to "disabled",
    "ktlint_standard_trailing-comma-on-call-site" to "disabled",
    "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
    "ktlint_standard_blank-line-before-declaration" to "disabled",
    "ktlint_standard_if-else-wrapping" to "disabled",
    "ktlint_standard_if-else-bracing" to "disabled",
    "ktlint_standard_multiline-if-else" to "disabled",
    "ktlint_standard_condition-wrapping" to "disabled",
    "ktlint_standard_chain-method-continuation" to "disabled",
    "ktlint_standard_property-wrapping" to "disabled",
    "ktlint_standard_no-blank-line-in-list" to "disabled",
    "ktlint_standard_enum-entry-name-case" to "disabled",
    "ktlint_standard_backing-property-naming" to "disabled",
    "ktlint_standard_filename" to "disabled",
    "ktlint_standard_max-line-length" to "disabled",
    "ktlint_standard_comment-wrapping" to "disabled",
    "ktlint_standard_kdoc" to "disabled",
    "ktlint_standard_kdoc-wrapping" to "disabled",
    "ktlint_standard_spacing-between-declarations-with-comments" to "disabled",
    "ktlint_standard_discouraged-comment-location" to "disabled",
    "ktlint_standard_annotation" to "disabled",
    "ktlint_standard_function-literal" to "disabled",
    "ktlint_standard_try-catch-finally-spacing" to "disabled",
    "ktlint_standard_no-consecutive-comments" to "disabled",
    "ktlint_standard_no-empty-first-line-in-class-body" to "disabled",
    "ktlint_standard_class-naming" to "disabled",
    "ktlint_standard_function-naming" to "disabled",
    "ktlint_standard_property-naming" to "disabled",
)

/**
 * Lint with Spotless: `./gradlew spotlessCheck` reports, `./gradlew spotlessApply` fixes. `check` runs the report.
 *
 * - Kotlin and Gradle scripts: ktlint in the IntelliJ style. Import order, spacing, indentation and unused imports are enforced; the rules that
 *   would rewrap lines, signatures and braces are off, so the layout of the code is the author's. It covers `tools/`, a build of its own.
 * - The reference client `DpopClient.java`: unused imports, trailing spaces, final newline.
 * - Scripts, SQL, YAML and configuration: trailing spaces and final newline.
 */
spotless {
    kotlin {
        target("src/**/*.kt", "tools/src/**/*.kt")
        ktlint("1.8.0").editorConfigOverride(ktlintStyle)
    }
    kotlinGradle {
        target("*.gradle.kts", "gradle/*.gradle.kts", "tools/*.gradle.kts")
        ktlint("1.8.0").editorConfigOverride(ktlintStyle)
    }
    java {
        target("deploy/**/*.java")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("text") {
        target(
            "**/*.sh", "**/*.sql", "**/*.yaml", "**/*.yml", "**/*.conf",
            "deploy/keycloak/dev-setup", "deploy/keycloak/admin-user", "deploy/postgres/backup", "tools/run",
        )
        targetExclude("**/build/**", "**/.gradle/**", "perf/results/**", "secrets/**", "github/**", "github.pub/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

extra["springModulithVersion"] = "2.1.1"
extra["testcontainersVersion"] = "2.0.5"
extra["bucket4jVersion"] = "8.21.0"
extra["archunitVersion"] = "1.5.1"
extra["nimbusVersion"] = "10.10"

/**
 * Jackson ahead of the Spring Boot release: 3.1.6 has four fixed high-severity advisories (GHSA-p6pp-m3f8-5c89, GHSA-7hhh-6rmp-j9qf,
 * GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54) that the Release workflow's scan refuses. Remove this when Spring Boot manages 3.1.7 or later.
 */
extra["jackson-bom.version"] = "3.1.7"

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
    implementation("com.bucket4j:bucket4j_jdk17-core:${property("bucket4jVersion")}")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation("tools.jackson.module:jackson-module-kotlin")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    developmentOnly("org.springframework.boot:spring-boot-docker-compose")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.modulith:spring-modulith-starter-core")
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
    testImplementation("com.tngtech.archunit:archunit:${property("archunitVersion")}")
    testImplementation("com.nimbusds:nimbus-jose-jwt:${property("nimbusVersion")}") // the tests import it; not in the Boot BOM, and the version Spring Security resolves
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
 * - Coverage: the XML report of Kover, which has the format of JaCoCo, so run `koverXmlReport` first. Kover covers the application
 *   only, so the code of the tools is excluded from the coverage, though it is still analysed.
 * - Scope: every file of the project, from its root, so that the tools, the deployment files, the perf scripts and the docs are analysed
 *   too. The tests are the `src/test` of the application and of the tools, indexed once as tests: the sources leave them out, because
 *   Sonar refuses a file indexed as both. The build output, the caches, the IDE folders and the raw results of the perf runs are left out.
 * - Needs Automatic Analysis switched off on the project, because the two cannot both feed it.
 */
// The DPoP client, which DpopClientTest runs in its own JVM. The tests run the classes compiled here for 17, under the JaCoCo agent,
// and each run writes its data; dpopClientReport turns the data into an XML report that SonarCloud reads beside the Kover one.
apply(plugin = "jacoco")

configure<JacocoPluginExtension> {
    toolVersion = "0.8.14"
}

// Kover measures the application in the test JVM. JaCoCo measures the client's classes in that same JVM, where the suite calls them
// in process, and the client's own JVMs; it does not measure anything else of the application, so the two do not overlap.
val clientClasses = listOf("dpop.*")
tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isEnabled = name == "test"
        includes = clientClasses
    }
}

val dpopClientClasses = layout.buildDirectory.dir("dpop-client/classes")
val dpopClientData = layout.buildDirectory.dir("dpop-client/data")
val dpopClientXml = layout.buildDirectory.file("reports/jacoco/dpopClient.xml")

// The runtime agent, which carries its own dependencies; the plain jar of the agent does not start a JVM.
val dpopClientAgent by configurations.creating
dependencies {
    dpopClientAgent("org.jacoco:org.jacoco.agent:0.8.14:runtime")
}

val compileDpopClient = tasks.register<JavaCompile>("compileDpopClient") {
    group = "verification"
    description = "Compiles deploy/keycloak/dpop/DpopClient.java for Java 25, for the tests that measure the client."
    source(fileTree("deploy/keycloak/dpop") { include("*.java") })
    classpath = files()
    destinationDirectory.set(dpopClientClasses)
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-proc:none", "-Xlint:all", "-Werror"))
}

tasks.test {
    dependsOn(compileDpopClient, dpopClientAgent)
    doFirst {
        delete(dpopClientData)
        systemProperty("dpopClient.classes", dpopClientClasses.get().asFile.path)
        systemProperty("dpopClient.agent", dpopClientAgent.singleFile.path)
        systemProperty("dpopClient.data", dpopClientData.get().asFile.path)
    }
}

// The same suite against the Kotlin reference, the executable JAR of tools/: the two implementations answer the same.
tasks.register<Test>("dpopJarParity") {
    group = "verification"
    description = "Runs DpopClientTest against the Kotlin DPoP client's executable JAR (tools/build/libs/dpop.jar)."
    dependsOn(gradle.includedBuild("tools").task(":dpopJar"))
    filter { includeTestsMatching("uy.ct.shortener.DpopClientTest") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    systemProperty("dpopClient.jar", gradle.includedBuild("tools").projectDir.resolve("build/libs/dpop.jar").path)
    useJUnitPlatform()
}

val dpopClientReport = tasks.register<JacocoReport>("dpopClientReport") {
    group = "verification"
    description = "Writes the JaCoCo report of the DPoP client, which SonarCloud reads beside the Kover one."
    executionData.setFrom(dpopClientData.map { data -> data.asFileTree.matching { include("*.exec") } }, layout.buildDirectory.dir("jacoco").map { dir -> dir.asFileTree.matching { include("test*.exec") } })
    classDirectories.setFrom(dpopClientClasses)
    sourceDirectories.setFrom(files("deploy/keycloak/dpop"))
    reports {
        xml.required.set(true)
        xml.outputLocation.set(dpopClientXml)
        html.required.set(false)
        csv.required.set(false)
    }
}

sonar {
    properties {
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.organization", "christ008")
        property("sonar.projectKey", "christ008_shortener")
        property("sonar.sources", ".")
        property("sonar.tests", "src/test,tools/src/test")
        property(
            "sonar.exclusions",
            listOf(
                "**/build/**", "**/.gradle/**", "**/.kotlin/**", "**/.idea/**", "**/.junie/**", "perf/results/**", "perf/data/**",
                "src/test/**", "tools/src/test/**",
            ).joinToString(","),
        )
        property("sonar.coverage.exclusions", "tools/src/main/**")
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            listOf(layout.buildDirectory.file("reports/kover/report.xml").get().asFile.path, dpopClientXml.get().asFile.path).joinToString(","),
        )
    }
}

/**
 * Mutation testing of the logic of the `shortlink`, `analytics` and `security` modules, by hand: `./gradlew mutationTest`. Not part of `check`
 * and not in CI. Results: the Testing section of docs/INTERNALS.md.
 *
 * - Scope: the domain types, the service, the redirect cache, the target-URL policy and the code generator, and in `security` the
 *   problem responder, the nonces, the rate limiter and its filter, and the small filters and converters of the chain.
 * - Tests: the ones that need no Docker, and the integration tests of `security`, which do. `TestIdp` starts again after each session
 *   of the launcher, because PIT opens several in one JVM.
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
        "uy.ct.shortener.analytics.internal.ClickAggregator*",
        "uy.ct.shortener.analytics.internal.CountKey*",
        "uy.ct.shortener.analytics.internal.ReferrerAdmission*",
        "uy.ct.shortener.analytics.internal.persistence.StoredDimension*",
        "uy.ct.shortener.analytics.Referrer*",
        "uy.ct.shortener.analytics.Language*",
        "uy.ct.shortener.analytics.internal.ClickFlusher*",
        "uy.ct.shortener.analytics.internal.ClickListener*",
        "uy.ct.shortener.analytics.internal.DeviceClass*",
        "uy.ct.shortener.analytics.internal.AnalyticsProperties*",
        "uy.ct.shortener.analytics.internal.DefaultClickStatsService*",
        "uy.ct.shortener.analytics.Granularity*",
        "uy.ct.shortener.analytics.StatsWindow*",
        "uy.ct.shortener.analytics.DeviceCounts*",
        "uy.ct.shortener.analytics.ClickStats*",
        "uy.ct.shortener.security.SecurityConfiguration*",
        "uy.ct.shortener.security.internal.AuthScheme*",
        "uy.ct.shortener.security.internal.ClientJwtAuthenticationConverter",
        "uy.ct.shortener.security.internal.DpopNonces*",
        "uy.ct.shortener.security.internal.HmacDpopNonces*",
        "uy.ct.shortener.security.internal.NoDpopNonces*",
        "uy.ct.shortener.security.internal.DpopNonceAuthenticationConverter",
        "uy.ct.shortener.security.internal.DpopNonceFilter",
        "uy.ct.shortener.security.internal.RateLimit*",
        "uy.ct.shortener.security.internal.SecurityEvents*",
        "uy.ct.shortener.security.internal.SecurityProblem*",
        "uy.ct.shortener.security.internal.SenderConstrainedBearerTokenResolver",
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
        "uy.ct.shortener.analytics.internal.ClickAggregatorTest",
        "uy.ct.shortener.analytics.internal.ClickFlusherTest",
        "uy.ct.shortener.analytics.internal.ClickListenerTest",
        "uy.ct.shortener.analytics.internal.DeviceClassifierTest",
        "uy.ct.shortener.analytics.internal.AnalyticsPropertiesTest",
        "uy.ct.shortener.analytics.internal.DefaultClickStatsServiceTest",
        "uy.ct.shortener.analytics.GranularityTest",
        "uy.ct.shortener.analytics.StatsWindowTest",
        "uy.ct.shortener.analytics.ClickStatsTest",
        "uy.ct.shortener.analytics.ReferrerTest",
        "uy.ct.shortener.analytics.LanguageTest",
        "uy.ct.shortener.analytics.internal.ReferrerAdmissionTest",
        "uy.ct.shortener.analytics.internal.persistence.StoredDimensionTest",
        "uy.ct.shortener.security.internal.DpopNoncesTest",
        "uy.ct.shortener.security.internal.RateLimiterTest",
        "uy.ct.shortener.security.internal.SecurityEventsTest",
        "uy.ct.shortener.security.internal.SecurityProblemResponderTest",
        "uy.ct.shortener.security.internal.DpopNonceAuthenticationConverterTest",
        "uy.ct.shortener.security.internal.SecurityFiltersTest",
        "uy.ct.shortener.security.DpopNoncesConfigurationTest",
        "uy.ct.shortener.security.DpopRequiredInProductionTest",
        "uy.ct.shortener.security.internal.*IntegrationTest",
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
        "deploy/keycloak/dpop/DpopClient.java",
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
    tasks.named<ProcessAot>("processAot") {
        args("--spring.profiles.active=production")
    }
}

/**
 * A JVM image from buildpacks (ADR 0036). The builder and the run image are pinned by digest; `health-checker` adds
 * `/workspace/health-check`. The flags are explained in INTERNALS.md#image.
 */
tasks.bootBuildImage {
    imageName = "shortener:${project.version}"
    builder = "bellsoft/buildpacks.builder:glibc@sha256:c7a8d5fcc863a7e5c36a6691d7ccd174cd4acd701a7338867e1bb7a61389de92"
    runImage = "bellsoft/buildpacks.hardened-run:glibc@sha256:81f455eb612bb818b37d3c02087fde31cd07a776334ed686f98906448b2eff42"
    buildpacks = listOf(
        "urn:cnb:builder:bellsoft/buildpacks/liberica",
        "docker://paketobuildpacks/syft:2.43.0",
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
