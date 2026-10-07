package uy.ct.shortener.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * `tools/run` is how the workflows, the shims of `dev-setup` and `smoke.sh`, the load-test scripts and a person at a prompt start
 * a tool. Gradle takes seconds even when it has nothing to build, so the script asks it only when a source is newer than the last
 * build, and it must say in words that a JDK older than 17 is not enough. It runs here in a copy of the repository's skeleton with
 * a `gradlew` and a launcher that only record what they were asked, so what is checked is when it builds and how it starts.
 */
class ToolsLauncherTest {

    @TempDir
    lateinit var root: Path

    private val longAgo = FileTime.from(Instant.now().minusSeconds(3600))

    private fun script(path: Path, body: String): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, "#!/bin/sh\n$body\n")
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"))
        return path
    }

    @BeforeEach
    fun skeleton() {
        Files.createDirectories(root.resolve("tools"))
        Files.copy(repositoryRoot.resolve("tools/run"), root.resolve("tools/run"))
        Files.setPosixFilePermissions(root.resolve("tools/run"), PosixFilePermissions.fromString("rwxr-xr-x"))
        listOf("tools/src/main/Main.kt", "tools/build.gradle.kts", "tools/settings.gradle.kts").forEach {
            Files.createDirectories(root.resolve(it).parent)
            Files.writeString(root.resolve(it), "// $it")
            Files.setLastModifiedTime(root.resolve(it), longAgo)
        }
        // A gradlew that writes down how it was called, and builds a launcher that says where it ran and with what.
        script(root.resolve("launcher-template"), "echo \"launcher in \$(pwd): \$*\"")
        script(
            root.resolve("gradlew"),
            """
            echo "${'$'}*" >> gradle-calls.txt
            [ ! -e fail-build ] || exit 1
            mkdir -p tools/build/install/tools/bin
            cp launcher-template tools/build/install/tools/bin/tools
            """.trimIndent(),
        )
    }

    private class Output(val status: Int, val stdout: String, val stderr: String)

    /** A java that says [version], and has the `lib/ct.sym` that only a JDK has, unless it is [asJre]. */
    private fun jdk(version: String, asJre: Boolean = false): Path {
        val home = root.resolve(if (asJre) "jre-$version" else "jdk-$version")
        script(
            home.resolve("bin/java"),
            "case \"\$*\" in *XshowSettings*) echo '    java.home = $home' >&2;; esac\n" +
                "if [ \"\${*#-version}\" != \"\$*\" ]; then echo 'openjdk version \"$version.0.1\" 2026-01-01' >&2; fi",
        )
        if (!asJre) Files.writeString(home.resolve("lib/ct.sym").also { Files.createDirectories(it.parent) }, "")
        return home
    }

    private fun start(javaHome: Path?, vararg arguments: String, onPath: Path? = null): Output {
        val builder = ProcessBuilder(root.resolve("tools/run").toString(), *arguments).directory(Path.of("/").toFile())
        builder.environment().remove("JAVA_HOME")
        javaHome?.let { builder.environment()["JAVA_HOME"] = it.toString() }
        onPath?.let { builder.environment()["PATH"] = it.resolve("bin").toString() + ":" + builder.environment()["PATH"] }
        val process = builder.start()
        val stdout = process.inputStream.readAllBytes().decodeToString()
        val stderr = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "tools/run did not finish" }
        return Output(process.exitValue(), stdout, stderr)
    }

    private fun builds(): List<String> = if (Files.exists(root.resolve("gradle-calls.txt"))) Files.readAllLines(root.resolve("gradle-calls.txt")) else emptyList()

    @Test
    fun `the first run builds the tools, then starts the tool from the root of the repository with the arguments it was given`() {
        val result = start(jdk("25"), "Smoke", "https://localhost", "second")

        assertThat(result.status).describedAs(result.stderr).isEqualTo(0)
        assertThat(builds()).containsExactly("--console=plain -q -p tools installDist")
        assertThat(result.stdout.trim()).isEqualTo("launcher in ${root.toRealPath()}: Smoke https://localhost second")
    }

    @Test
    fun `a run after it does not ask Gradle again while no source has changed`() {
        val java = jdk("25")
        start(java, "Smoke")
        start(java, "Report", "gc", "log")
        val third = start(java, "Realms")

        assertThat(third.status).isEqualTo(0)
        assertThat(builds()).describedAs("one build for three runs").hasSize(1)
    }

    @Test
    fun `a source that is newer than the last build is built again, whichever of them it is`() {
        val java = jdk("25")
        start(java, "Smoke")

        listOf("tools/src/main/Main.kt", "tools/build.gradle.kts", "tools/settings.gradle.kts").forEachIndexed { index, source ->
            Files.setLastModifiedTime(root.resolve(source), FileTime.from(Instant.now().plusSeconds(60)))
            start(java, "Smoke")

            assertThat(builds()).describedAs("after changing $source").hasSize(index + 2)
            // Back to old, so that the next source is the only one that is newer than the build.
            Files.setLastModifiedTime(root.resolve(source), longAgo)
        }
        start(java, "Smoke")
        assertThat(builds()).describedAs("and quiet again once nothing is newer").hasSize(4)
    }

    @Test
    fun `a build that fails is said, does not start the tool, and is tried again by the next run`() {
        Files.writeString(root.resolve("fail-build"), "")
        val java = jdk("25")

        val failed = start(java, "Smoke")

        assertThat(failed.status).isEqualTo(1)
        assertThat(failed.stderr).contains("Smoke: the tools could not be built")
        assertThat(failed.stdout).isEmpty()

        Files.delete(root.resolve("fail-build"))
        val again = start(java, "Smoke")
        assertThat(again.status).describedAs(again.stderr).isEqualTo(0)
        assertThat(builds()).hasSize(2)
    }

    @Test
    fun `a JDK older than 25 is refused in words, naming the tool and the version found`() {
        val result = start(jdk("21"), "Smoke")

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("Smoke: needs a JDK 25 or newer, and found 21")
        assertThat(builds()).isEmpty()
    }

    @Test
    fun `a JDK 25 is enough`() {
        assertThat(start(jdk("25"), "Smoke").status).isEqualTo(0)
    }

    @Test
    fun `the version of a JDK is read from its release file, without starting it`() {
        val home = root.resolve("jdk-with-release")
        script(home.resolve("bin/java"), "echo 'java was started' >&2; exit 3")
        Files.writeString(home.resolve("lib/ct.sym").also { Files.createDirectories(it.parent) }, "")
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25.0.4.1\"\nIMPLEMENTOR=\"x\"\n")

        val accepted = start(home, "Smoke")
        assertThat(accepted.status).describedAs(accepted.stderr).isEqualTo(0)
        assertThat(accepted.stderr).doesNotContain("java was started")

        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"1.8.0_402\"\n")
        val refused = start(home, "Smoke")
        assertThat(refused.status).isEqualTo(1)
        assertThat(refused.stderr).contains("found 8")
    }

    @Test
    fun `building needs a JDK, and a JRE is refused in words before Gradle is asked`() {
        val jre = jdk("25", asJre = true)

        val result = start(jre, "Smoke")

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("Smoke: building the tools needs a JDK, and $jre is not one")
        assertThat(builds()).isEmpty()
    }

    @Test
    fun `the home of a java on the PATH is the one that is checked`() {
        val result = start(null, "Smoke", onPath = jdk("25", asJre = true))

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("building the tools needs a JDK, and ${root.resolve("jre-25")} is not one")
        assertThat(builds()).isEmpty()
        assertThat(start(null, "Smoke", onPath = jdk("25")).status).isEqualTo(0)
    }

    @Test
    fun `a JRE can start the tools that are already built`() {
        start(jdk("25"), "Smoke")

        val result = start(jdk("25", asJre = true), "Smoke")

        assertThat(result.status).describedAs(result.stderr).isEqualTo(0)
        assertThat(builds()).hasSize(1)
    }

    @Test
    fun `a java that is not there is said in words`() {
        val result = start(root.resolve("nowhere"), "Smoke")

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("needs a JDK 25 or newer, and found none")
    }

    @Test
    fun `no tool at all is a usage error`() {
        assertThat(start(jdk("25")).status).isNotEqualTo(0)
    }

    @Test
    fun `the shims start the tool they are named for`() {
        assertThat(Files.readString(repositoryRoot.resolve("deploy/keycloak/dev-setup"))).contains("tools/run\" DevSetup")
        assertThat(Files.readString(repositoryRoot.resolve("perf/smoke.sh"))).contains("tools/run\" Smoke")
    }
}
