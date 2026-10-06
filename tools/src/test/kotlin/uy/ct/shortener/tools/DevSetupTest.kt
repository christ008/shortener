package uy.ct.shortener.tools

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `DevSetup` makes the keys, the realm and the passwords of a development setup, and every developer, CI run and rehearsal of
 * the stack depends on it. It runs here in process, in a copy of the one file it needs, with no one to ask (so with its defaults,
 * as CI does), and what it wrote is read back. The keys are made by the DPoP client in a process of its own, as when it is run
 * by hand. What it asks on a terminal is the same code with another source for the same values, and runs here against a prompt
 * that answers from a list.
 */
class DevSetupTest {

    @TempDir
    lateinit var tree: Path

    private val settings = listOf(
        "DEV_KEYS_DIR", "DEV_KEYCLOAK_ADMIN_USER", "DEV_KEYCLOAK_ADMIN_PASSWORD", "DEV_USER_ALICE_PASSWORD", "DEV_USER_BOB_PASSWORD",
        "DEV_POSTGRES_PASSWORD", "DEV_APP_PASSWORD", "DEV_MIGRATOR_PASSWORD", "DEV_EXPORTER_PASSWORD",
    )

    @BeforeEach
    fun copyWhatItNeeds() {
        Files.createDirectories(tree.resolve("deploy/keycloak"))
        Files.copy(repositoryRoot.resolve("deploy/keycloak/shortener-realm.template.json"), tree.resolve("deploy/keycloak/shortener-realm.template.json"))
    }

    private fun setUp(vararg arguments: String, environment: Map<String, String> = emptyMap(), prompt: Prompt? = null) =
        run(DevSetup, *arguments, environment = environment, root = tree, prompt = prompt)

    /** A person who answers each question with the next of [answers], and an empty line when there are none left. */
    private class Answers(vararg answers: String) : Prompt {
        private val remaining = answers.toMutableList()
        val questions = mutableListOf<String>()

        private fun next(question: String): String {
            questions += question
            return if (remaining.isEmpty()) "" else remaining.removeFirst()
        }

        override fun line(question: String) = next(question)

        override fun secret(question: String) = next(question)
    }

    private fun env(): Map<String, String> =
        Files.readAllLines(tree.resolve(".env")).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=').trim('\'') }

    private fun realm(): Map<String, Any?> = parseJson(Files.readString(tree.resolve("deploy/keycloak/shortener-realm.json")))

    private fun key(client: String) = Files.readString(tree.resolve("deploy/keycloak/dev-keys/$client.jwk.json"))

    @Test
    fun `makes four keys, a realm that trusts them and a private env file, and a second run changes nothing until it is forced`() {
        val first = setUp("--yes")

        assertThat(first.status).describedAs(first.stderr).isEqualTo(0)
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
        val second = setUp("--yes")
        assertThat(second.status).isEqualTo(0)
        assertThat(second.stderr).contains("nothing was changed")
        assertThat(env() to key("demo-client")).isEqualTo(before)

        val forced = setUp("--yes", "--force")
        assertThat(forced.status).describedAs(forced.stderr).isEqualTo(0)
        assertThat(key("demo-client")).isNotEqualTo(before.second)
        assertThat(env()["DEV_APP_PASSWORD"]).isNotEqualTo(before.first["DEV_APP_PASSWORD"])
    }

    @Test
    fun `a setting given in the environment is used, in the realm and in the env file, and other lines of the env file survive`() {
        Files.writeString(tree.resolve(".env"), "SOMETHING_ELSE='kept'\nDEV_APP_PASSWORD='old'\n")

        val result = setUp("--yes", "--force", environment = mapOf("DEV_USER_ALICE_PASSWORD" to "Given-Alice-Password-1", "DEV_KEYCLOAK_ADMIN_USER" to "root"))

        assertThat(result.status).describedAs(result.stderr).isEqualTo(0)
        assertThat(env()).containsEntry("SOMETHING_ELSE", "kept").containsEntry("DEV_USER_ALICE_PASSWORD", "Given-Alice-Password-1").containsEntry("DEV_KEYCLOAK_ADMIN_USER", "root")
        assertThat(env()["DEV_APP_PASSWORD"]).isNotEqualTo("old")
        assertThat(Files.readAllLines(tree.resolve(".env")).count { it.startsWith("DEV_APP_PASSWORD=") }).isEqualTo(1)
        assertThat(Files.readString(tree.resolve("deploy/keycloak/shortener-realm.json"))).contains("Given-Alice-Password-1")
    }

    @Test
    fun `a value that would break the env file is refused before anything is written`() {
        val result = setUp("--yes", environment = mapOf("DEV_APP_PASSWORD" to "it's"))

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("must not contain quotes")
        assertThat(tree.resolve(".env")).doesNotExist()
        assertThat(tree.resolve("deploy/keycloak/dev-keys")).doesNotExist()
    }

    @Test
    fun `show prints what is set up, and says so when nothing is`() {
        assertThat(setUp("--show").status).isEqualTo(1)

        setUp("--yes", environment = mapOf("DEV_POSTGRES_PASSWORD" to "pg-password-for-show"))
        val shown = setUp("--show")

        assertThat(shown.status).isEqualTo(0)
        assertThat(shown.stdout).contains("Keycloak console", "pg-password-for-show", "deploy/keycloak/dev-keys")
    }

    @Test
    fun `an option it does not know ends with usage and status 2`() {
        val result = setUp("--nope")

        assertThat(result.status).isEqualTo(2)
        assertThat(result.stderr).contains("unknown option --nope", "usage:")
    }

    @Test
    fun `on a terminal it asks for each setting, takes the default for an empty answer and a chosen password as typed`() {
        // The nine settings in the order they are asked: the keys directory, the console user and its password, alice, bob, and the four of Postgres.
        val person = Answers("", "root", "", "Chosen-Alice-Password-1")

        val result = setUp(prompt = person)

        assertThat(result.status).describedAs(result.stderr).isEqualTo(0)
        assertThat(person.questions).hasSize(9)
        assertThat(person.questions[0]).contains("Where the client keys go", "[deploy/keycloak/dev-keys]")
        assertThat(person.questions[2]).contains("Keycloak console password", "[a random one]")
        assertThat(result.stdout).contains("Press Enter to accept what is in brackets")
        assertThat(env()).containsEntry("DEV_KEYS_DIR", "deploy/keycloak/dev-keys").containsEntry("DEV_KEYCLOAK_ADMIN_USER", "root")
        assertThat(env()["DEV_KEYCLOAK_ADMIN_PASSWORD"]).matches("[A-Za-z0-9]{24}")
        assertThat(env()["DEV_USER_ALICE_PASSWORD"]).isEqualTo("Chosen-Alice-Password-1")
    }

    @Test
    fun `on a terminal it asks before replacing a setup that exists, and leaves it alone unless the answer is yes`() {
        setUp("--yes")
        val before = env() to key("demo-client")

        val declined = setUp(prompt = Answers("n"))
        assertThat(declined.status).isEqualTo(0)
        assertThat(declined.stderr).contains("nothing was changed")
        assertThat(env() to key("demo-client")).isEqualTo(before)

        val accepted = setUp(prompt = Answers("y"))
        assertThat(accepted.status).describedAs(accepted.stderr).isEqualTo(0)
        assertThat(key("demo-client")).isNotEqualTo(before.second)
    }

    @Test
    fun `a setting in the environment is not asked for, even on a terminal, and --yes does not ask at all`() {
        val person = Answers()

        setUp("--yes", prompt = person)
        assertThat(person.questions).isEmpty()

        Files.delete(tree.resolve(".env"))
        Files.delete(tree.resolve("deploy/keycloak/shortener-realm.json"))
        val partly = Answers()
        setUp(environment = settings.filter { it != "DEV_POSTGRES_PASSWORD" }.associateWith { if (it == "DEV_KEYS_DIR") "deploy/keycloak/dev-keys" else "given-$it" }, prompt = partly)
        assertThat(partly.questions).hasSize(1)
        assertThat(partly.questions.single()).contains("Postgres password (user myuser)")
    }
}
