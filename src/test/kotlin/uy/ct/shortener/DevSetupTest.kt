package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.json.JsonParserFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * `tools/DevSetup.java` makes the keys, the realm and the passwords of a development setup, and every developer, CI run and
 * rehearsal of the stack depends on it. This runs the real file as a subprocess in a copy of the few files it needs, without a
 * terminal (so with its defaults, as CI does), and reads what it wrote. What it asks on a terminal is the same code with
 * another source for the same values, and was tried by hand on a pseudo-terminal.
 */
class DevSetupTest {

    @TempDir
    lateinit var tree: Path

    private class Output(val exitCode: Int, val stdout: String, val stderr: String)

    private val settings = listOf(
        "DEV_KEYS_DIR", "DEV_KEYCLOAK_ADMIN_USER", "DEV_KEYCLOAK_ADMIN_PASSWORD", "DEV_USER_ALICE_PASSWORD", "DEV_USER_BOB_PASSWORD",
        "DEV_POSTGRES_PASSWORD", "DEV_APP_PASSWORD", "DEV_MIGRATOR_PASSWORD", "DEV_EXPORTER_PASSWORD",
    )

    @BeforeEach
    fun copyWhatItNeeds() {
        Files.createDirectories(tree.resolve("deploy/keycloak"))
        Files.createDirectories(tree.resolve("tools"))
        listOf("deploy/keycloak/DpopClient.java", "deploy/keycloak/shortener-realm.template.json").forEach {
            Files.copy(Path.of(it), tree.resolve(it))
        }
        Files.list(Path.of("tools")).use { it.filter { f -> f.toString().endsWith(".java") }.forEach { f -> Files.copy(f, tree.resolve("tools").resolve(f.fileName)) } }
    }

    private fun run(vararg arguments: String, environment: Map<String, String> = emptyMap()): Output {
        val builder = ProcessBuilder(System.getProperty("java.home") + "/bin/java", "tools/DevSetup.java", *arguments).directory(tree.toFile())
        builder.environment().keys.removeIf { it.startsWith("DEV_") }
        builder.environment().putAll(environment)
        val process = builder.start()
        val stdout = process.inputStream.readAllBytes().decodeToString()
        val stderr = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "DevSetup.java did not finish" }
        return Output(process.exitValue(), stdout, stderr)
    }

    private fun env(): Map<String, String> =
        Files.readAllLines(tree.resolve(".env")).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim('\'') }

    private fun realm(): Map<String, Any?> = JsonParserFactory.getJsonParser().parseMap(Files.readString(tree.resolve("deploy/keycloak/shortener-realm.json")))

    private fun key(client: String) = Files.readString(tree.resolve("deploy/keycloak/dev-keys/$client.jwk.json"))

    @Test
    fun `makes four keys, a realm that trusts them and a private env file, and a second run changes nothing until it is forced`() {
        val first = run("--yes")

        assertThat(first.exitCode).describedAs(first.stderr).isEqualTo(0)
        assertThat(first.stdout).contains("development setup", "FOR LOCAL DEVELOPMENT ONLY", "Done. What to do next")
        val clients = listOf("demo-client", "other-client", "admin-client", "no-scope-client")
        clients.forEach {
            assertThat(key(it)).contains("\"kid\":\"$it-key-1\"", "\"d\":")
            assertThat(Files.getPosixFilePermissions(tree.resolve("deploy/keycloak/dev-keys/$it.jwk.json")).map { p -> p.name }).contains("OTHERS_READ")
        }
        @Suppress("UNCHECKED_CAST")
        val realmClients = (realm()["clients"] as List<Map<String, Any?>>).associateBy { it["clientId"] as String }
        clients.forEach { client ->
            @Suppress("UNCHECKED_CAST")
            val trusted = (realmClients.getValue(client)["attributes"] as Map<String, String>)["jwks.string"]
            assertThat(trusted).contains("\"kid\":\"$client-key-1\"").doesNotContain("\"d\":")
            assertThat(trusted!!.substringAfter("\"x\":\"").substringBefore('"')).isEqualTo(key(client).substringAfter("\"x\":\"").substringBefore('"'))
        }
        assertThat(env().keys).containsExactlyInAnyOrderElementsOf(settings)
        assertThat(Files.getPosixFilePermissions(tree.resolve(".env")).map { it.name }).containsExactlyInAnyOrder("OWNER_READ", "OWNER_WRITE")
        env().filterKeys { it.endsWith("PASSWORD") }.values.forEach { assertThat(it).matches("[A-Za-z0-9]{24}") }
        assertThat(env().filterKeys { it.endsWith("PASSWORD") }.values.toSet()).describedAs("every password is its own").hasSize(7)

        @Suppress("UNCHECKED_CAST")
        val users = (realm()["users"] as List<Map<String, Any?>>).associate { it["username"] as String to ((it["credentials"] as List<Map<String, Any?>>).single()["value"]) }
        assertThat(users).containsEntry("alice", env()["DEV_USER_ALICE_PASSWORD"]).containsEntry("bob", env()["DEV_USER_BOB_PASSWORD"])

        val before = env() to key("demo-client")
        val second = run("--yes")
        assertThat(second.exitCode).isEqualTo(0)
        assertThat(second.stderr).contains("nothing was changed")
        assertThat(env() to key("demo-client")).isEqualTo(before)

        val forced = run("--yes", "--force")
        assertThat(forced.exitCode).describedAs(forced.stderr).isEqualTo(0)
        assertThat(key("demo-client")).isNotEqualTo(before.second)
        assertThat(env()["DEV_APP_PASSWORD"]).isNotEqualTo(before.first["DEV_APP_PASSWORD"])
    }

    @Test
    fun `a setting given in the environment is used, in the realm and in the env file, and other lines of the env file survive`() {
        Files.writeString(tree.resolve(".env"), "SOMETHING_ELSE='kept'\nDEV_APP_PASSWORD='old'\n")

        val result = run("--yes", "--force", environment = mapOf("DEV_USER_ALICE_PASSWORD" to "Given-Alice-Password-1", "DEV_KEYCLOAK_ADMIN_USER" to "root"))

        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
        assertThat(env()).containsEntry("SOMETHING_ELSE", "kept").containsEntry("DEV_USER_ALICE_PASSWORD", "Given-Alice-Password-1").containsEntry("DEV_KEYCLOAK_ADMIN_USER", "root")
        assertThat(env()["DEV_APP_PASSWORD"]).isNotEqualTo("old")
        assertThat(Files.readAllLines(tree.resolve(".env")).count { it.startsWith("DEV_APP_PASSWORD=") }).isEqualTo(1)
        assertThat(Files.readString(tree.resolve("deploy/keycloak/shortener-realm.json"))).contains("Given-Alice-Password-1")
    }

    @Test
    fun `a value that would break the env file is refused before anything is written`() {
        val result = run("--yes", environment = mapOf("DEV_APP_PASSWORD" to "it's"))

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("must not contain quotes")
        assertThat(tree.resolve(".env")).doesNotExist()
        assertThat(tree.resolve("deploy/keycloak/dev-keys")).doesNotExist()
    }

    @Test
    fun `show prints what is set up, and says so when nothing is`() {
        assertThat(run("--show").exitCode).isEqualTo(1)

        run("--yes", environment = mapOf("DEV_POSTGRES_PASSWORD" to "pg-password-for-show"))
        val shown = run("--show")

        assertThat(shown.exitCode).isEqualTo(0)
        assertThat(shown.stdout).contains("Keycloak console", "pg-password-for-show", "deploy/keycloak/dev-keys")
    }

    @Test
    fun `an option it does not know ends with usage and status 2`() {
        val result = run("--nope")

        assertThat(result.exitCode).isEqualTo(2)
        assertThat(result.stderr).contains("unknown option --nope", "usage:")
    }
}
