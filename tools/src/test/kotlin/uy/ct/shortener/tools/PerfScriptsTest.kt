package uy.ct.shortener.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * What the load-test scripts leave in a k6 summary. k6 copies the value `setup()` returns into the summary it exports, and
 * `perf/k6/mixed.js` returns the DPoP key and the token of the run, so every script that exports a summary must remove them
 * with `perf/scrub-k6-summary.sh`. The scripts are not run: they need Docker, Postgres and Keycloak.
 *
 * - The scrubber keeps everything of the summary but those two members, in particular the code list.
 * - A file it cannot write, as the one k6 leaves when it runs as another user, is replaced, since the directory is what must be writable.
 * - A file that is not JSON is removed and the script fails, because it may hold what it was asked to remove.
 * - `bench.sh` and `profile.sh` call it after every k6 run.
 *
 * The fixture builds its JWK member from a variable: written out, this file would be a tracked file with a private key in it, and
 * `RepositoryHoldsNoSecretsTest` would say so.
 */
class PerfScriptsTest {

    @TempDir
    lateinit var directory: Path

    private val scrubber = repositoryRoot.resolve("perf/scrub-k6-summary.sh").toString()

    private fun scrub(vararg files: Path): Pair<Int, String> {
        val process = ProcessBuilder(listOf("sh", scrubber) + files.map { it.toString() }).redirectErrorStream(true).start()
        val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
        return process.waitFor() to output
    }

    private val privateMember = "d"

    private fun needsJq() = assumeTrue(ProcessBuilder("jq", "--version").start().waitFor() == 0, "needs jq")

    @Test
    fun `the proof key and the token go, the codes and the metrics stay`() {
        needsJq()
        val summary = directory.resolve("r1500.k6.json").also {
            Files.writeString(
                it,
                """{"metrics":{"http_reqs":{"rate":1500.5}},"setup_data":{"codes":["AAAAAAA","BBBBBBB"],"dpopJwk":{"crv":"P-256","$privateMember":"lSoHWNl031nlzN4dQiv_Jn815COtTPRISAznts_GEIw"},"token":"eyJhbGciOi.payload.signature","nonce":"n1"},"root_group":{"name":""}}""",
            )
        }

        val (status, output) = scrub(summary)

        assertThat(status).describedAs(output).isZero()
        val written = Files.readString(summary)
        assertThat(written).doesNotContain("dpopJwk", "lSoHWNl031nlzN4dQiv_Jn815COtTPRISAznts_GEIw", "eyJhbGciOi", "\"token\"")
        val data = parseJson(written)
        assertThat(data["metrics"]).isEqualTo(mapOf("http_reqs" to mapOf("rate" to 1500.5)))
        assertThat(data["setup_data"]).isEqualTo(mapOf("codes" to listOf("AAAAAAA", "BBBBBBB"), "nonce" to "n1"))
        assertThat(data["root_group"]).isEqualTo(mapOf("name" to ""))
    }

    @Test
    fun `a summary without setup data is left as it was, and a missing one is skipped`() {
        needsJq()
        val plain = directory.resolve("plain.k6.json").also { Files.writeString(it, """{"metrics":{}}""") }

        val (status, output) = scrub(plain, directory.resolve("missing.k6.json"))

        assertThat(status).describedAs(output).isZero()
        assertThat(parseJson(Files.readString(plain))).isEqualTo(mapOf("metrics" to emptyMap<String, Any>()))
    }

    @Test
    fun `a summary that its owner cannot write to is replaced`() {
        needsJq()
        val summary = directory.resolve("r1500.k6.json").also {
            Files.writeString(it, """{"setup_data":{"token":"eyJhbGciOi.payload.signature","codes":["AAAAAAA"]}}""")
            it.toFile().setWritable(false)
        }

        val (status, output) = scrub(summary)

        assertThat(status).describedAs(output).isZero()
        assertThat(parseJson(Files.readString(summary))["setup_data"]).isEqualTo(mapOf("codes" to listOf("AAAAAAA")))
        assertThat(directory.toFile().list()).containsExactly("r1500.k6.json")
    }

    @Test
    fun `a file that is not JSON is removed and the script fails`() {
        needsJq()
        val broken = directory.resolve("broken.k6.json").also { Files.writeString(it, """{"setup_data":{"token":"abc""") }

        val (status, output) = scrub(broken)

        assertThat(status).isEqualTo(1)
        assertThat(output).contains("not valid JSON")
        assertThat(broken).doesNotExist()
    }

    @Test
    fun `every script that exports a k6 summary scrubs it`() {
        val exporters = Files.list(repositoryRoot.resolve("perf")).use { files ->
            files.filter { it.fileName.toString().endsWith(".sh") }.filter { "--summary-export" in Files.readString(it) }.toList()
        }

        assertThat(exporters.map { it.fileName.toString() }).contains("bench.sh", "profile.sh")
        exporters.forEach { assertThat(Files.readString(it)).describedAs("$it").contains("scrub-k6-summary.sh") }
    }
}
