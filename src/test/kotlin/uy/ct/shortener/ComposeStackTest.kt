package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds the production stack, `compose.prod.yaml` and its observability overlay, to the standard it is documented at.
 * It reads the files, so a change that loosens one of these fails here and not in production.
 *
 * - Every service is read-only, has no capabilities, cannot gain privileges, has a memory limit and rotated logs.
 * - Only the edge publishes ports, and nothing is privileged, shares the host's namespaces or mounts the Docker socket.
 * - Only the migration job holds the credentials of the role that owns the tables, so code execution in the
 *   application cannot drop them, and only it is a job that migrates and stops.
 * - Writable memory uses the long `volumes:` syntax, because Swarm silently drops the short `tmpfs:` key.
 * - The database network has no route out, and the images are pinned to a version.
 */
class ComposeStackTest {

    private val migratorSecret = "db_migrator_password"

    private val files = listOf("compose.prod.yaml", "compose.prod.observability.yaml").map(::load)

    private val services: Map<String, Map<String, Any?>> = files.flatMap { it.map("services").entries }.associate { it.key to it.value.asMap() }

    @Suppress("UNCHECKED_CAST")
    private fun load(path: String): Map<String, Any?> = Yaml().load<Map<String, Any?>>(Files.readString(Path.of(path)))

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

    private fun Map<String, Any?>.map(key: String): Map<String, Any?> = this[key].asMap()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Any?> = this[key] as? List<Any?> ?: emptyList()

    private fun secretsOf(service: Map<String, Any?>): Set<String> =
        service.list("secrets").map { if (it is Map<*, *>) it["source"] as String else it as String }.toSet()

    private fun environmentOf(service: Map<String, Any?>) = service.map("environment").mapValues { it.value.toString() }

    @Test
    fun `every service is read only, has no capabilities, cannot gain privileges and is bounded`() {
        services.forEach { (name, service) ->
            assertThat(service["read_only"]).describedAs("$name read_only").isEqualTo(true)
            assertThat(service.list("cap_drop")).describedAs("$name cap_drop").containsExactly("ALL")
            assertThat(service.list("security_opt")).describedAs("$name security_opt").contains("no-new-privileges:true")
            assertThat(service.map("deploy").map("resources").map("limits")["memory"]).describedAs("$name memory limit").isNotNull
            assertThat(service.map("logging").map("options")["max-size"]).describedAs("$name log rotation").isNotNull
        }
    }

    @Test
    fun `every service but the database runs as a user that is not root`() {
        services.filterKeys { it != "postgres" }.forEach { (name, service) ->
            val user = service["user"].toString()
            assertThat(user).describedAs("$name user").isNotEqualTo("null").doesNotStartWith("0").isNotEqualTo("root")
        }
    }

    @Test
    fun `only the database adds capabilities, and only those its entrypoint needs to hand over to the postgres user`() {
        assertThat(services.filter { it.value.list("cap_add").isNotEmpty() }.keys).containsExactly("postgres")
        assertThat(services.getValue("postgres").list("cap_add")).containsExactlyInAnyOrder("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID")
    }

    @Test
    fun `only the edge publishes ports, in host mode so that it sees the client's address`() {
        val publishing = services.filter { it.value.list("ports").isNotEmpty() }
        assertThat(publishing.keys).containsExactly("edge")
        publishing.getValue("edge").list("ports").forEach { assertThat((it as Map<*, *>)["mode"]).isEqualTo("host") }
        assertThat(publishing.getValue("edge").list("ports").map { (it as Map<*, *>)["published"] }).containsExactlyInAnyOrder(80, 443)
    }

    @Test
    fun `nothing is privileged, shares the host's namespaces or mounts the Docker socket`() {
        services.forEach { (name, service) ->
            assertThat(service["privileged"]).describedAs("$name privileged").isNotEqualTo(true)
            assertThat(service["network_mode"]).describedAs("$name network_mode").isNull()
            assertThat(service["pid"]).describedAs("$name pid").isNull()
            assertThat(service["ipc"]).describedAs("$name ipc").isNull()
            assertThat(service.list("volumes").map { it.toString() }).describedAs("$name volumes").noneMatch { it.contains("docker.sock") }
        }
    }

    @Test
    fun `writable memory is mounted with the long volumes syntax, which Swarm honours, and never with tmpfs`() {
        services.forEach { (name, service) ->
            assertThat(service).describedAs("$name must not use the short tmpfs key").doesNotContainKey("tmpfs")
            val tmpfs = service.list("volumes").filterIsInstance<Map<*, *>>().filter { it["type"] == "tmpfs" }
            tmpfs.forEach { assertThat((it["tmpfs"] as Map<*, *>)["size"]).describedAs("$name tmpfs size").isNotNull }
        }
        assertThat(services.getValue("edge").list("volumes").map { (it as Map<*, *>)["target"] }).contains("/tmp")
        assertThat(services.getValue("shortener").list("volumes").map { (it as Map<*, *>)["target"] }).contains("/tmp")
    }

    @Test
    fun `only the migration job holds the credentials of the role that owns the tables`() {
        assertThat(secretsOf(services.getValue("migrate"))).contains(migratorSecret)
        services.filterKeys { it != "migrate" && it != "postgres" }.forEach { (name, service) ->
            assertThat(secretsOf(service)).describedAs("secrets of $name").doesNotContain(migratorSecret)
        }
    }

    @Test
    fun `the application gets no Flyway settings of its own`() {
        assertThat(environmentOf(services.getValue("shortener")).keys).noneMatch { it.startsWith("SPRING_FLYWAY_") }
        val targets = services.getValue("shortener").list("secrets").filterIsInstance<Map<*, *>>().map { it["target"] }
        assertThat(targets).containsExactly("spring.datasource.password")
    }

    @Test
    fun `the migration job migrates and stops, with Flyway on its own connection`() {
        val migrate = services.getValue("migrate")
        val environment = environmentOf(migrate)

        assertThat(environment["SHORTENER_MIGRATE_ONLY"]).isEqualTo("true")
        assertThat(environment.keys).contains("SPRING_FLYWAY_URL", "SPRING_FLYWAY_USER")
        assertThat(migrate.map("deploy")["mode"]).isEqualTo("replicated-job")
        assertThat(migrate["restart"]).isEqualTo("no")
        val targets = migrate.list("secrets").filterIsInstance<Map<*, *>>().map { it["target"] }
        assertThat(targets).contains("spring.flyway.password")
    }

    @Test
    fun `the database has no route out and only the stack's own services attach to it`() {
        val data = files.first().map("networks").map("data")
        assertThat(data["internal"]).isEqualTo(true)
        assertThat(services.getValue("edge").list("networks").ifEmpty { services.getValue("edge").map("networks").keys.toList() })
            .doesNotContain("data")
        assertThat(services.getValue("migrate").map("networks").keys.ifEmpty { services.getValue("migrate").list("networks").toSet() })
            .containsExactly("data")
    }

    @Test
    fun `the services the stack depends on have a health check, and the application's is the readiness probe`() {
        listOf("edge", "shortener", "postgres").forEach { name ->
            assertThat(services.getValue(name).map("healthcheck")["test"]).describedAs("$name health check").isNotNull
        }
        assertThat(environmentOf(services.getValue("shortener"))["THC_PATH"]).isEqualTo("/actuator/health/readiness")
    }

    @Test
    fun `third party images are pinned to a version`() {
        services.filterKeys { it !in setOf("shortener", "migrate") }.forEach { (name, service) ->
            val image = service["image"].toString()
            assertThat(image).describedAs("$name image").contains(":").doesNotContain(":latest")
        }
    }

    @Test
    fun `the application is started in the production profile, with its secrets read from files`() {
        listOf("shortener", "migrate").forEach { name ->
            val environment = environmentOf(services.getValue(name))
            assertThat(environment["SPRING_PROFILES_ACTIVE"]).describedAs("$name profile").isEqualTo("production")
            assertThat(environment["SPRING_CONFIG_IMPORT"]).describedAs("$name config import").isEqualTo("configtree:/run/secrets/")
            assertThat(environment.keys).describedAs("$name must not carry passwords in the environment").noneMatch { it.endsWith("PASSWORD") }
        }
    }
}
