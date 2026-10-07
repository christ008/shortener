package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds the repository to two security invariants: development cryptographic material is generated on the machine that uses it,
 * and production secrets are never repository configuration. It asks Git, so what it checks is what a clone would carry.
 *
 * - The files that `dev-setup` and the stack write (`.env`, `secrets/`, client keys, the realms made from them) are ignored.
 * - No tracked file holds a private key: a PEM block, or a JWK with its private member `d`.
 * - `perf/results/` is out of the second check. The k6 summaries there keep the setup data of each run: a throwaway DPoP
 *   proof key and a token of the local development realm, long expired. Remove it from them and this exception goes.
 */
class RepositoryHoldsNoSecretsTest {

    private val ignoredPaths = listOf(
        ".env",
        "secrets/tls_key",
        "secrets/db_app_password",
        "deploy/stack/local/secrets/tls_key",
        "deploy/keycloak/dev-keys/demo-client.jwk.json",
        "deploy/keycloak/shortener-realm.json",
        "deploy/keycloak/shortener-realm.production.json",
    )

    private val pemPrivateKey = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----\\s*[A-Za-z0-9+/=\\s]{40,}")

    private val jwkPrivateMember = Regex("\"d\"\\s*:\\s*\"[A-Za-z0-9_-]{20,}\"")

    private fun git(vararg arguments: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("git") + arguments).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        return process.waitFor() to output
    }

    private fun trackedFiles(): List<String> {
        val (status, output) = runCatching { git("ls-files", "-z") }.getOrDefault(-1 to "")
        assumeTrue(status == 0, "needs a Git checkout")
        return output.split('\u0000').filter { it.isNotBlank() }
    }

    @Test
    fun `what dev-setup and the stack write is ignored by Git`() {
        trackedFiles()

        ignoredPaths.forEach { path ->
            assertThat(git("check-ignore", "--quiet", path).first).describedAs("$path is ignored").isZero()
        }
    }

    @Test
    fun `no tracked file holds a private key`() {
        val offenders = trackedFiles()
            .filterNot { it.startsWith("perf/results/") }
            .map(Path::of)
            .filter(Files::isRegularFile)
            .filter { path -> runCatching { Files.readString(path) }.getOrNull()?.let { pemPrivateKey.containsMatchIn(it) || jwkPrivateMember.containsMatchIn(it) } == true }

        assertThat(offenders).describedAs("tracked files with a private key").isEmpty()
    }
}
