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
 * - Keycloak, when the stack has one, is held to the same rules, is not published, and is the only service that holds its
 *   passwords besides the database that creates its role.
 */
class ComposeStackTest {

    private val migratorSecret = "db_migrator_password"

    private val files = listOf("compose.prod.yaml", "compose.prod.observability.yaml", "compose.prod.keycloak.yaml", "compose.prod.postgres-ha.yaml").map(::load)

    private val services: Map<String, Map<String, Any?>> = files
        .flatMap { it.map("services").entries }
        .groupBy({ it.key }, { it.value.asMap() })
        .mapValues { (_, parts) -> parts.reduce(::merged) }

    /** What Compose does with a service named in two files: maps merge, lists add up, and the later scalar wins. */
    @Suppress("UNCHECKED_CAST")
    private fun merged(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> =
        (base.keys + overlay.keys).associateWith { key ->
            val a = base[key]
            val b = overlay[key]
            when {
                a == null -> b
                b == null -> a
                a is Map<*, *> && b is Map<*, *> -> merged(a as Map<String, Any?>, b as Map<String, Any?>)
                a is List<*> && b is List<*> -> a + b
                else -> b
            }
        }

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
        services.filterKeys { it != "postgres" && it != "postgres-replica" }.forEach { (name, service) ->
            val user = service["user"].toString()
            assertThat(user).describedAs("$name user").isNotEqualTo("null").doesNotStartWith("0").isNotEqualTo("root")
        }
    }

    @Test
    fun `only the database adds capabilities, and only those its entrypoint needs to hand over to the postgres user`() {
        assertThat(services.filter { it.value.list("cap_add").isNotEmpty() }.keys).containsExactlyInAnyOrder("postgres", "postgres-replica")
        listOf("postgres", "postgres-replica").forEach {
            assertThat(services.getValue(it).list("cap_add")).describedAs("$it cap_add").containsExactlyInAnyOrder("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID")
        }
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
    fun `both networks are encrypted between nodes, since the edge carries tokens and the data network carries rows`() {
        val networks = files.first().map("networks")
        listOf("edge", "data").forEach { name ->
            assertThat(networks.map(name).map("driver_opts")["encrypted"]).describedAs("$name network encrypted").isEqualTo("true")
        }
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

    @Test
    fun `keycloak is not published, runs optimized from files and keeps its secrets to itself`() {
        val keycloak = services.getValue("keycloak")
        val environment = environmentOf(keycloak)

        assertThat(keycloak.list("ports")).isEmpty()
        assertThat(keycloak.list("command")).contains("--optimized")
        assertThat(environment.keys).describedAs("keycloak must not carry passwords in the environment").noneMatch { it.endsWith("PASSWORD") }
        assertThat(environment.keys).contains("KC_DB_PASSWORD_FILE", "KC_BOOTSTRAP_ADMIN_PASSWORD_FILE")
        assertThat(environment["KC_HTTP_ENABLED"]).describedAs("TLS ends at the edge").isEqualTo("true")
        assertThat(environment["KC_PROXY_HEADERS"]).isEqualTo("xforwarded")
        assertThat(secretsOf(keycloak)).containsExactlyInAnyOrder("db_keycloak_password", "keycloak_admin_password")
        services.filterKeys { it !in setOf("keycloak", "postgres") }.forEach { (name, service) ->
            assertThat(secretsOf(service)).describedAs("secrets of $name").doesNotContain("db_keycloak_password", "keycloak_admin_password")
        }
        assertThat(secretsOf(services.getValue("postgres"))).contains("db_keycloak_password").doesNotContain("keycloak_admin_password")
    }

    @Test
    fun `keycloak writes only to memory, and reaches the database and the edge but is not the way out`() {
        val keycloak = services.getValue("keycloak")

        assertThat(keycloak.list("volumes").filterIsInstance<Map<*, *>>().map { it["target"] })
            .containsExactlyInAnyOrder("/tmp", "/opt/keycloak/data/tmp")
        assertThat(keycloak.list("networks")).containsExactlyInAnyOrder("edge", "data")
    }

    @Test
    fun `the edge forwards only the shortener realm and its static files to keycloak`() {
        val conf = Files.readAllLines(Path.of("deploy/edge/keycloak.conf")).filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        val locations = Regex("""(?m)^location\s+(?:=\s+|~\s+)?(\S+)""").findAll(conf).map { it.groupValues[1] }.toList()

        assertThat(locations).containsExactly("/realms/shortener/protocol/openid-connect/token", "^/(realms/shortener|resources)/")
        assertThat(conf).doesNotContain("/admin").doesNotContain("realms/master")
        assertThat(conf).contains("limit_req zone=token")
    }

    @Test
    fun `the primary archives its WAL with pgBackRest at least every five minutes, and bounds what a missing replica can keep`() {
        val command = services.getValue("postgres").list("command").map { it.toString() }

        assertThat(command).contains("archive_mode=on", "archive_timeout=300", "wal_level=replica", "max_slot_wal_keep_size=4GB")
        assertThat(command).anyMatch { it.startsWith("archive_command=pgbackrest --stanza=shortener archive-push") }
        assertThat(services.getValue("postgres").list("volumes").filterIsInstance<String>()).contains("pgbackrest-data:/var/lib/pgbackrest")
        services.filterKeys { it != "postgres" }.forEach { (name, service) ->
            assertThat(service.list("volumes").map { it.toString() }).describedAs("$name must not mount the backups").noneMatch { it.contains("pgbackrest-data") }
        }
    }

    @Test
    fun `the replica is a read only copy on its own volume and its own node, that nothing publishes or reads`() {
        val replica = services.getValue("postgres-replica")
        val primary = services.getValue("postgres")

        assertThat(replica.list("ports")).isEmpty()
        assertThat(replica.list("networks")).containsExactly("data")
        assertThat(replica.list("entrypoint")).containsExactly("/usr/local/bin/shortener-replica-entrypoint.sh")
        assertThat(replica.list("volumes").map { it.toString() }).anyMatch { it.startsWith("postgres-replica-data:") }
        assertThat(primary.list("volumes").map { it.toString() }).noneMatch { it.contains("postgres-replica-data") }
        val constraints = replica.map("deploy").map("placement").list("constraints") + primary.map("deploy").map("placement").list("constraints")
        assertThat(constraints).describedAs("the replica and the primary are told to different nodes").doesNotHaveDuplicates()
        services.filterKeys { it != "postgres" && it != "postgres-replica" }.forEach { (name, service) ->
            assertThat(environmentOf(service).values.joinToString()).describedAs("$name must not point at the replica").doesNotContain("postgres-replica")
        }
    }

    @Test
    fun `the replication password is held by the two databases and nobody else`() {
        services.filterKeys { it != "postgres" && it != "postgres-replica" }.forEach { (name, service) ->
            assertThat(secretsOf(service)).describedAs("secrets of $name").doesNotContain("db_replicator_password")
        }
        assertThat(secretsOf(services.getValue("postgres-replica"))).containsExactly("db_replicator_password")
        assertThat(secretsOf(services.getValue("postgres"))).contains("db_replicator_password")
        assertThat(environmentOf(services.getValue("postgres-replica")).keys).noneMatch { it.endsWith("PASSWORD") }
    }
}
