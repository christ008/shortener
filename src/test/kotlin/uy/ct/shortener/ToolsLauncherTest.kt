package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/**
 * `tools/run` is how the workflows, the shims of `dev-setup` and `smoke.sh` and a person at a prompt start a tool. It must say in
 * words that a JDK older than 25 is not enough, because the JDK says it with a message about a preview feature, and it must
 * start the tool from the root of the repository with the arguments it was given.
 */
class ToolsLauncherTest {

    @TempDir
    lateinit var directory: Path

    private class Output(val exitCode: Int, val stdout: String, val stderr: String)

    /** A `java` that reports [version] and, when it is asked to run something, prints where it is and what it was given. */
    private fun fakeJava(version: String): Path {
        val bin = Files.createDirectories(directory.resolve("jdk-$version/bin"))
        val java = bin.resolve("java")
        Files.writeString(java, "#!/bin/sh\nif [ \"$1\" = -version ]; then echo 'openjdk version \"$version.0.1\" 2026-01-01' >&2; exit 0; fi\necho \"ran in $(pwd) with $*\"\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"))
        return bin.parent
    }

    private fun run(javaHome: Path?, vararg arguments: String): Output {
        val builder = ProcessBuilder("tools/run", *arguments).directory(Path.of("").toAbsolutePath().toFile())
        builder.environment().remove("JAVA_HOME")
        javaHome?.let { builder.environment()["JAVA_HOME"] = it.toString() }
        val process = builder.start()
        val stdout = process.inputStream.readAllBytes().decodeToString()
        val stderr = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "tools/run did not finish" }
        return Output(process.exitValue(), stdout, stderr)
    }

    @Test
    fun `a JDK older than 25 is refused in words, naming the tool and the version found`() {
        val result = run(fakeJava("21"), "Smoke")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("Smoke: needs JDK 25, and found 21")
        assertThat(result.stdout).isEmpty()
    }

    @Test
    fun `a JDK 25 starts the tool from the root of the repository with the arguments it was given`() {
        val result = run(fakeJava("25"), "Smoke", "https://localhost", "second")

        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
        assertThat(result.stdout.trim()).isEqualTo("ran in ${Path.of("").toAbsolutePath()} with tools/Smoke.java https://localhost second")
    }

    @Test
    fun `a tool that does not exist is a usage error, whichever JDK is there`() {
        val result = run(fakeJava("25"), "Nonsense")

        assertThat(result.exitCode).isEqualTo(2)
        assertThat(result.stderr).contains("there is no tool Nonsense")
    }

    @Test
    fun `no tool at all is a usage error`() {
        assertThat(run(fakeJava("25")).exitCode).isNotEqualTo(0)
    }

    @Test
    fun `a java that is not there is said in words`() {
        val result = run(directory.resolve("nowhere"), "Smoke")

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("needs java 25, and found none")
    }

    @Test
    fun `the shims start the tool they are named for`() {
        assertThat(Files.readString(Path.of("deploy/keycloak/dev-setup"))).contains("tools/run\" DevSetup")
        assertThat(Files.readString(Path.of("perf/smoke.sh"))).contains("tools/run\" Smoke")
    }
}
