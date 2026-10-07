package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Runs `deploy/stack/deploy.sh` for a first deploy, in a scratch directory, with stand-ins for `docker` and `cosign` that
 * only write down how they were called. Nothing reaches a daemon or a registry.
 *
 * - A name in `.env` that is not a plain variable name stops the script before anything is deployed, because the file is
 *   read by `eval`.
 * - Unless `VERIFY_SIGNATURE=never`, the image of the version is checked with `cosign verify` against the identity of the
 *   release workflow for that tag, and a failed check, a missing `cosign` or an image outside ghcr.io deploys nothing.
 * - The check comes before the first `docker stack deploy`.
 */
class DeployScriptTest {

    @TempDir
    lateinit var work: Path

    private lateinit var bin: Path

    private val calls get() = work.resolve("calls.log").let { if (Files.exists(it)) Files.readAllLines(it) else emptyList() }

    @BeforeEach
    fun `a scratch repository with the files the script hashes`() {
        bin = Files.createDirectory(work.resolve("bin"))
        Files.createSymbolicLink(work.resolve("deploy"), Path.of("deploy").toAbsolutePath())
        stub("docker", "echo \"docker \$*\" >>\"$work/calls.log\"")
    }

    private fun stub(name: String, body: String) {
        val file = bin.resolve(name)
        Files.writeString(file, "#!/bin/sh\n$body\n")
        file.toFile().setExecutable(true)
    }

    private fun deploy(vararg environment: Pair<String, String>, version: String = "1.2.3"): Result {
        val builder = ProcessBuilder("sh", Path.of("deploy/stack/deploy.sh").toAbsolutePath().toString(), version)
            .directory(work.toFile())
            .redirectErrorStream(true)
        builder.environment().apply {
            put("PATH", "$bin:/usr/bin:/bin")
            remove("SHORTENER_IMAGE")
            remove("VERIFY_SIGNATURE")
            putAll(environment)
        }
        val process = builder.start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "deploy.sh did not finish" }
        return Result(process.exitValue(), output)
    }

    private data class Result(val exit: Int, val output: String)

    @Test
    fun `refuses a name in dot env that is not a plain variable name, and deploys nothing`() {
        Files.writeString(work.resolve(".env"), "GOOD=1\nBAD;touch pwned=1\n")

        val result = deploy("VERIFY_SIGNATURE" to "never")

        assertThat(result.exit).isNotZero()
        assertThat(result.output).contains("is not a variable name")
        assertThat(work.resolve("pwned")).doesNotExist()
        assertThat(calls).noneMatch { it.contains("stack deploy") }
    }

    @Test
    fun `reads dot env, and what is already in the environment wins`() {
        Files.writeString(work.resolve(".env"), "# a comment\nFROM_ENV_FILE='kept'\nALREADY=file\n")
        stub("docker", "echo \"docker \$* FROM_ENV_FILE=\$FROM_ENV_FILE ALREADY=\$ALREADY\" >>\"$work/calls.log\"")

        val result = deploy("VERIFY_SIGNATURE" to "never", "ALREADY" to "environment")

        assertThat(result.exit).isZero()
        assertThat(calls).anyMatch { it.contains("stack deploy") && it.contains("FROM_ENV_FILE=kept") && it.contains("ALREADY=environment") }
    }

    @Test
    fun `verifies the signature of the version against the release workflow of the tag before it deploys`() {
        stub("cosign", "echo \"cosign \$*\" >>\"$work/calls.log\"")

        val result = deploy()

        assertThat(result.exit).isZero()
        assertThat(calls.first { it.startsWith("cosign") }).isEqualTo(
            "cosign verify ghcr.io/christ008/shortener:1.2.3" +
                " --certificate-identity https://github.com/christ008/shortener/.github/workflows/release.yml@refs/tags/v1.2.3" +
                " --certificate-oidc-issuer https://token.actions.githubusercontent.com",
        )
        assertThat(calls.indexOfFirst { it.startsWith("cosign") }).isLessThan(calls.indexOfFirst { it.contains("stack deploy") })
    }

    @Test
    fun `deploys nothing when the signature does not verify`() {
        stub("cosign", "exit 1")

        val result = deploy()

        assertThat(result.exit).isNotZero()
        assertThat(result.output).contains("could not be verified")
        assertThat(calls).noneMatch { it.contains("stack deploy") }
    }

    @Test
    fun `deploys nothing when cosign is not installed`() {
        val result = deploy()

        assertThat(result.exit).isNotZero()
        assertThat(result.output).contains("cosign is needed")
        assertThat(calls).noneMatch { it.contains("stack deploy") }
    }

    @Test
    fun `an image that is not from ghcr dot io cannot be verified, unless the check is turned off`() {
        stub("cosign", "echo \"cosign \$*\" >>\"$work/calls.log\"")

        val refused = deploy("SHORTENER_IMAGE" to "shortener")
        assertThat(refused.exit).isNotZero()
        assertThat(refused.output).contains("only images of ghcr.io are signed")

        val skipped = deploy("SHORTENER_IMAGE" to "shortener", "VERIFY_SIGNATURE" to "never")
        assertThat(skipped.exit).isZero()
        assertThat(calls).noneMatch { it.startsWith("cosign") }
    }
}
