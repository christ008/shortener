package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import org.yaml.snakeyaml.Yaml
import uy.ct.shortener.security.internal.SecurityProperties
import uy.ct.shortener.shortlink.internal.RedirectCacheProperties
import java.nio.file.Files
import java.nio.file.Path

/**
 * Holds the production stack, `deploy/stack/compose.prod.yaml` and its observability overlay, to the standard it is documented at.
 * It reads the files, so a change that loosens one of these fails here and not in production.
 *
 * - Every service is read-only, has no capabilities, cannot gain privileges, has a memory limit and rotated logs.
 * - Only the edge publishes ports, and nothing is privileged, shares the host's namespaces or mounts the Docker socket.
 * - Only the migration job holds the credentials of the role that owns the tables, so code execution in the
 *   application cannot drop them, and only it is a job that migrates and stops.
 * - Writable memory uses the long `volumes:` syntax, because Swarm silently drops the short `tmpfs:` key.
 * - The networks: every one but the edge's and the application's way out is internal, the edge shares exactly one network with each
 *   service it proxies to, and the application trusts forwarded headers from that network's subnet alone. Keycloak has no route out.
 * - The images are pinned to a version.
 * - Keycloak, when the stack has one, is held to the same rules, is not published, and is the only service that holds its
 *   passwords besides the database that creates its role. Its database URL is a variable, so a promoted replica needs no file edit.
 * - The variables the files read and the ones `deploy/stack/.env.example` lists are the same, and the defaults the stack gives
 *   the application's tunables are the application's own.
 */
class ComposeStackTest {

    private val migratorSecret = "db_migrator_password"

    private val files = listOf(
        "deploy/stack/compose.prod.yaml",
        "deploy/stack/overlays/compose.observability.yaml",
        "deploy/stack/overlays/compose.keycloak.yaml",
        "deploy/stack/overlays/compose.postgres-ha.yaml",
        "deploy/stack/overlays/compose.console.yaml",
        "deploy/stack/overlays/compose.pki-ca.yaml",
        "deploy/stack/overlays/compose.pki-db.yaml",
        "deploy/stack/overlays/compose.pki.yaml",
    ).map(::load)

    private val services: Map<String, Map<String, Any?>> = files
        .flatMap { it.map("services").entries }
        .groupBy({ it.key }, { it.value.asMap() })
        .mapValues { (_, parts) -> parts.reduce(::merged) }

    /** What Compose does with a service named in two files: maps merge, lists add up, and the later scalar wins. */
    private fun merged(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> =
        (base.keys + overlay.keys).associateWith { key ->
            val a = base[key]
            val b = overlay[key]
            when {
                a == null -> b
                b == null -> a
                a is Map<*, *> && b is Map<*, *> -> merged(a.asMap(), b.asMap())
                a is List<*> && b is List<*> -> a + b
                else -> b
            }
        }

    private fun load(path: String): Map<String, Any?> = Yaml().load<Any?>(Files.readString(Path.of(path))).asMap()

    private val networks: Map<String, Map<String, Any?>> = files
        .flatMap { it.map("networks").entries }
        .groupBy({ it.key }, { it.value.asMap() })
        .mapValues { (_, parts) -> parts.reduce(::merged) }

    private fun networksOf(service: String): Set<String> =
        when (val networks = services.getValue(service)["networks"]) {
            is List<*> -> networks.map(Any?::toString).toSet()
            else -> networks.asMap().keys
        }

    private fun membersOf(network: String): Set<String> = services.keys.filter { network in networksOf(it) }.toSet()

    private fun secretsOf(service: Map<String, Any?>): Set<String> =
        service.list("secrets").map(::sourceOf).toSet()

    /** A secret of a service is named by a string, or by a map whose `source` is the name. */
    private fun sourceOf(secret: Any?): String = when (secret) {
        is String -> secret
        else -> secret.asMap().text("source")
    }

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
    fun `only the database and the CA add capabilities, and only those their entrypoints need`() {
        assertThat(services.filter { it.value.list("cap_add").isNotEmpty() }.keys).containsExactlyInAnyOrder("postgres", "postgres-replica", "stepca")
        listOf("postgres", "postgres-replica").forEach {
            assertThat(services.getValue(it).list("cap_add")).describedAs("$it cap_add").containsExactlyInAnyOrder("CHOWN", "DAC_OVERRIDE", "FOWNER", "SETGID", "SETUID")
        }
        assertThat(services.getValue("stepca").list("cap_add")).describedAs("the binary has the file capability, and does not start without it").containsExactly("NET_BIND_SERVICE")
    }

    @Test
    fun `only the edge publishes ports, in host mode so that it sees the client's address`() {
        val publishing = services.filter { it.value.list("ports").isNotEmpty() }
        assertThat(publishing.keys).containsExactly("edge")
        publishing.getValue("edge").list("ports").maps().forEach { assertThat(it["mode"]).isEqualTo("host") }
        assertThat(publishing.getValue("edge").list("ports").maps().map { it["published"] }).containsExactlyInAnyOrder(80, 443)
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
            val tmpfs = service.list("volumes").maps().filter { it["type"] == "tmpfs" }
            tmpfs.forEach { assertThat(it.map("tmpfs")["size"]).describedAs("$name tmpfs size").isNotNull }
        }
        assertThat(services.getValue("edge").list("volumes").maps().map { it["target"] }).contains("/tmp")
        assertThat(services.getValue("shortener").list("volumes").maps().map { it["target"] }).contains("/tmp")
    }

    @Test
    fun `the directory Keycloak caches its gzipped files in exists in the image, open to its user, because a tmpfs takes its mode from it`() {
        val keycloak = Files.readString(Path.of("deploy/keycloak/Dockerfile"))
        assertThat(keycloak).contains("RUN mkdir -m 1777 /opt/keycloak/data/tmp")
    }

    @Test
    fun `only the migration job holds the credentials of the role that owns the tables`() {
        assertThat(secretsOf(services.getValue("migrate"))).contains(migratorSecret)
        services.filterKeys { it != "migrate" && it != "postgres" }.forEach { (name, service) ->
            assertThat(secretsOf(service)).describedAs("secrets of $name").doesNotContain(migratorSecret)
        }
        assertThat(secretsOf(services.getValue("migrate"))).contains("pki_migrator_cert", "pki_migrator_key_der")
        services.filterKeys { it != "migrate" }.forEach { (name, service) ->
            assertThat(secretsOf(service)).describedAs("the migrator's identity is the job's alone, as $name shows").noneMatch { it.startsWith("pki_migrator") }
        }
    }

    @Test
    fun `the application gets no Flyway settings of its own`() {
        assertThat(environmentOf(services.getValue("shortener")).keys).noneMatch { it.startsWith("SPRING_FLYWAY_") }
        val targets = services.getValue("shortener").list("secrets").filterIsInstance<Map<*, *>>().mapNotNull { it["target"] }
        assertThat(targets).contains("spring.datasource.password", "shortener.security.dpop.nonce.secret").noneMatch { it.toString().startsWith("spring.flyway") }
        assertThat(secretsOf(services.getValue("shortener"))).noneMatch { it.startsWith("pki_migrator") }
    }

    @Test
    fun `the DPoP nonce secret is the application's alone, and the migration job, which serves no requests, turns nonces off`() {
        services.filterKeys { it != "shortener" }.forEach { (name, service) ->
            assertThat(secretsOf(service)).describedAs("secrets of $name").doesNotContain("dpop_nonce_secret")
        }
        assertThat(secretsOf(services.getValue("shortener"))).contains("dpop_nonce_secret")
        assertThat(environmentOf(services.getValue("migrate"))).containsEntry("SHORTENER_SECURITY_DPOP_NONCE_ENABLED", "false")
        assertThat(environmentOf(services.getValue("shortener"))).doesNotContainKey("SHORTENER_SECURITY_DPOP_NONCE_ENABLED")
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
    fun `the migration job decides its targets, since the production profile does not start the context until they are decided`() {
        val environment = environmentOf(services.getValue("migrate"))

        assertThat(environment["SPRING_PROFILES_ACTIVE"]).isEqualTo("production")
        assertThat(environment["SHORTENER_SHORTLINK_TARGETURLS_ALLOWANY"])
            .describedAs("without this the job migrates, fails to start the context, and is retried until it gives up")
            .isEqualTo("true")
    }

    @Test
    fun `the database has no route out and only the stack's own services attach to it`() {
        val data = files.first().map("networks").map("data")
        assertThat(data["internal"]).isEqualTo(true)
        assertThat(networksOf("edge")).doesNotContain("data")
        assertThat(networksOf("migrate")).containsExactly("data")
    }

    @Test
    fun `the networks between services are internal and encrypted between nodes, and only the edge's and the way out have a route out`() {
        listOf("ingress", "data", "idp-front").forEach { name ->
            assertThat(networks.getValue(name).map("driver_opts")["encrypted"]).describedAs("$name network encrypted").isEqualTo("true")
            assertThat(networks.getValue(name)["internal"]).describedAs("$name network internal").isEqualTo(true)
        }
        assertThat(networks.keys).containsExactlyInAnyOrder("edge", "ingress", "idp-front", "data", "egress", "pki")
        listOf("edge", "egress").forEach { assertThat(networks.getValue(it)["internal"]).describedAs("$it network internal").isNull() }
        assertThat(networks.getValue("pki")["internal"]).describedAs("the CA's network has no route out").isEqualTo(true)
    }

    @Test
    fun `only the edge is on the edge network and only the application is on the way out`() {
        assertThat(membersOf("edge")).containsExactly("edge")
        assertThat(membersOf("egress")).containsExactly("shortener")
        assertThat(membersOf("ingress")).containsExactlyInAnyOrder("edge", "shortener")
        services.keys.filter { it !in setOf("edge", "shortener") }.forEach {
            assertThat(networksOf(it)).describedAs("$it has no route out").doesNotContain("edge", "egress", "ingress")
        }
    }

    @Test
    fun `the edge shares exactly one network with each service it proxies to, and the application trusts forwarded headers from that network alone`() {
        assertThat(networksOf("edge") intersect networksOf("shortener")).describedAs("Docker's DNS answers with one shared network, so a second one makes the edge arrive from an address the application does not trust").containsExactly("ingress")
        assertThat(networksOf("edge") intersect networksOf("keycloak")).containsExactly("idp-front")
        assertThat(networksOf("keycloak")).containsExactlyInAnyOrder("idp-front", "data")

        val subnet = networks.getValue("ingress").map("ipam").list("config").single().asMap()["subnet"]
        assertThat(subnet).isNotNull
        assertThat(environmentOf(services.getValue("shortener"))["SERVER_TOMCAT_REMOTEIP_INTERNALPROXIES"])
            .describedAs("the subnet the edge arrives from and the one the application trusts").isEqualTo(subnet.toString())
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
            assertThat(environment["SHORTENER_SECURITY_DPOP_REQUIRED"]).describedAs("$name must not turn DPoP off, which production refuses").isIn(null, "true")
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
        assertThat(environment["KC_DB_URL"]).describedAs("the stack's Postgres by default, and a variable for a promoted replica")
            .isEqualTo("\${KEYCLOAK_DB_URL:-jdbc:postgresql://postgres:5432/keycloak}")
        assertThat(secretsOf(keycloak)).containsExactlyInAnyOrder("db_keycloak_password", "keycloak_admin_password")
        services.filterKeys { it !in setOf("keycloak", "postgres") }.forEach { (name, service) ->
            assertThat(secretsOf(service)).describedAs("secrets of $name").doesNotContain("db_keycloak_password", "keycloak_admin_password")
        }
        assertThat(secretsOf(services.getValue("postgres"))).contains("db_keycloak_password").doesNotContain("keycloak_admin_password")
    }

    @Test
    fun `keycloak writes only to memory, and reaches the database and the edge but has no route out`() {
        val keycloak = services.getValue("keycloak")

        assertThat(keycloak.list("volumes").filterIsInstance<Map<*, *>>().map { it["target"] })
            .containsExactlyInAnyOrder("/tmp", "/opt/keycloak/data/tmp")
        assertThat(networksOf("keycloak").map { networks.getValue(it)["internal"] }).containsOnly(true)
    }

    @Test
    fun `the edge forwards only the shortener realm and its static files to keycloak`() {
        val conf = Files.readAllLines(Path.of("deploy/edge/keycloak.conf")).filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        val locations = Regex("""(?m)^location\s+(?:=\s+|~\s+)?(\S+)""").findAll(conf).map { it.groupValues[1] }.toList()

        assertThat(locations).describedAs("in this order, since the first regular expression that matches wins").containsExactly(
            "/realms/shortener/protocol/openid-connect/token",
            "^/realms/shortener/clients-registrations/openid-connect(/[^/]+)?$",
            "^/realms/shortener/clients-registrations/",
            "^/(realms/shortener|resources)/",
        )
        assertThat(conf).doesNotContain("/admin").doesNotContain("realms/master")
        assertThat(conf).contains("limit_req zone=token", "limit_req zone=register")
    }

    @Test
    fun `a client registers through the OpenID Connect endpoint alone, and the rest of the registration service answers 404`() {
        val conf = Files.readAllLines(Path.of("deploy/edge/keycloak.conf")).filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        val blocks = Regex("""(?s)location\s+~\s+(\S+)\s*\{(.*?)\n}""").findAll(conf).associate { it.groupValues[1] to it.groupValues[2] }

        assertThat(blocks.getValue("^/realms/shortener/clients-registrations/openid-connect(/[^/]+)?$")).contains("limit_req zone=register", "proxy_pass")
        assertThat(blocks.getValue("^/realms/shortener/clients-registrations/")).contains("return 404").doesNotContain("proxy_pass")
        assertThat(Files.readString(Path.of("deploy/edge/nginx.conf"))).contains("zone=register:10m rate=6r/m")
    }

    @Test
    fun `the console overlay adds one file of locations to the edge, and publishes and mounts nothing else`() {
        val overlay = load("deploy/stack/overlays/compose.console.yaml")

        assertThat(overlay.map("services").keys).containsExactly("edge")
        val edge = overlay.map("services").map("edge")
        assertThat(edge.keys).describedAs("what the overlay sets on the edge").containsExactly("configs")
        assertThat(edge.list("configs").filterIsInstance<Map<*, *>>().map { it["target"] }).containsExactly("/etc/nginx/locations/console.conf")
        assertThat(overlay.map("configs")["edge-console"].asMap()["file"]).isEqualTo("../edge/console.conf")
    }

    @Test
    fun `the console answers 404 to everyone but the node itself, in every location, before it proxies anything`() {
        val conf = Files.readAllLines(Path.of("deploy/edge/console.conf")).filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        val blocks = Regex("""(?s)location\s+\^~\s+(\S+)\s*\{(.*?)\n}""").findAll(conf).associate { it.groupValues[1] to it.groupValues[2] }

        assertThat(blocks.keys).describedAs("the console and the master realm, and nothing else").containsExactly("/admin/", "/realms/master/")
        blocks.forEach { (path, body) ->
            val guard = Regex("""if \(\${'$'}remote_addr != ([0-9.]+)\) \{\s*return 404;\s*}""").find(body)
            assertThat(guard).describedAs("the guard of $path").isNotNull
            assertThat(guard!!.groupValues[1]).describedAs("the address of the node, the gateway of docker_gwbridge").isEqualTo("172.18.0.1")
            assertThat(body.indexOf("if (\$remote_addr")).describedAs("the guard of $path comes first").isLessThan(body.indexOf("proxy_pass"))
        }
        val public = Files.readAllLines(Path.of("deploy/edge/keycloak.conf")).filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        assertThat(public).describedAs("the public file does not name the console").doesNotContain("/admin").doesNotContain("realms/master")
    }

    @Test
    fun `the administrator script is valid shell, and refuses what it should before it touches Docker`() {
        val script = Path.of("deploy/keycloak/admin-user")
        fun run(vararg args: String): Pair<Int, String> {
            val process = ProcessBuilder("sh", script.toString(), *args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        }

        assertThat(Files.isExecutable(script)).isTrue
        assertThat(ProcessBuilder("sh", "-n", script.toString()).start().waitFor()).describedAs("sh -n").isZero()
        assertThat(run().first).describedAs("no arguments").isEqualTo(2)
        assertThat(run("destroy", "ana").first).describedAs("an unknown command").isEqualTo(2)
        assertThat(run("create", "an a").first).describedAs("a name with a space").isEqualTo(2)
        val bootstrap = run("create", "admin")
        assertThat(bootstrap.first).describedAs("the bootstrap administrator is not made again").isEqualTo(2)
        assertThat(bootstrap.second).contains("must not be the bootstrap administrator")
        val text = Files.readString(script)
        assertThat(text).describedAs("it asks for the app before it retires the one with a password").contains("CONFIGURE_TOTP", "grep -qx otp", "defaultAction=true", "otpPolicyAlgorithm=HmacSHA512", "otpPolicyDigits=8")
    }

    @Test
    fun `the copy of the backups is made by the node, and refuses what it should before it touches Docker or AWS`() {
        val script = Path.of("deploy/postgres/backup")
        fun run(environment: Map<String, String>, vararg args: String): Pair<Int, String> {
            val builder = ProcessBuilder("sh", script.toString(), *args).redirectErrorStream(true)
            builder.environment().apply { remove("OFFSITE_BUCKET"); put("PG_CONTAINER", "none"); putAll(environment) }
            val process = builder.start()
            val output = process.inputStream.bufferedReader().readText()
            return process.waitFor() to output
        }

        assertThat(ProcessBuilder("sh", "-n", script.toString()).start().waitFor()).describedAs("sh -n").isZero()
        val withoutBucket = run(emptyMap(), "offsite")
        assertThat(withoutBucket.first).describedAs("no bucket").isNotZero()
        assertThat(withoutBucket.second).contains("OFFSITE_BUCKET")
        assertThat(run(mapOf("OFFSITE_BUCKET" to "b", "AWS_CLI" to "/bin/true"), "offsite", "everything").first).describedAs("an argument it does not know").isEqualTo(2)
        assertThat(run(mapOf("OFFSITE_BUCKET" to "b", "AWS_CLI" to "/nonexistent/aws"), "offsite").second).contains("AWS CLI is not installed")
        val text = Files.readString(script)
        assertThat(text).describedAs("the WAL goes before the backups, and nothing is deleted from the bucket")
            .containsSubsequence("archive/", "backup/").doesNotContain("--delete").doesNotContain("s3 rm")
    }

    @Test
    fun `the schedule of the copy sends the WAL every five minutes and the backups after they are made, and the role cannot delete`() {
        val cron = Files.readAllLines(Path.of("deploy/postgres/offsite.cron")).filterNot { it.startsWith("#") || it.isBlank() }
        assertThat(cron).anyMatch { it.startsWith("*/5 * * * *") && it.contains("backup offsite wal") }
        assertThat(cron).anyMatch { it.startsWith("47 3 * * *") && it.contains("backup offsite ") }
        assertThat(cron).contains("OFFSITE_BUCKET=BUCKET_NAME")
        assertThat(cron.first { it.startsWith("PATH=") }).describedAs("the AWS CLI is a snap").contains("/snap/bin")
        cron.filter { it.contains("backup offsite") }.forEach { assertThat(it).contains("root").contains("/srv/shortener/deploy/postgres/backup").contains("logger -t shortener-offsite") }

        val offsite = Files.readString(Path.of("deploy/aws/offsite.sh"))
        assertThat(ProcessBuilder("sh", "-n", "deploy/aws/offsite.sh").start().waitFor()).describedAs("sh -n").isZero()
        assertThat(offsite).contains("\"Sid\\\":\\\"NeverDelete\\\",\\\"Effect\\\":\\\"Deny\\\"", "s3:DeleteObjectVersion", "s3:PutLifecycleConfiguration", "aws:SecureTransport", "BlockPublicPolicy=true", "Status=Enabled")
        assertThat(offsite).describedAs("it asks before it deletes the backups").contains("YES=1")
    }

    @Test
    fun `every proxied location replaces the forwarded headers the application reads and empties the ones it does not`() {
        val confs = Files.list(Path.of("deploy/edge")).use { files -> files.filter { it.toString().endsWith(".conf") && !it.fileName.toString().startsWith("upstream") }.toList() }
        val lines = confs.flatMap { Files.readAllLines(it) }.filterNot { it.trimStart().startsWith("#") }.map { it.trim() }
        val proxied = lines.count { it.startsWith("proxy_pass ") || it == "include /etc/nginx/upstream.conf;" }

        assertThat(proxied).isGreaterThan(0)
        mapOf(
            "proxy_set_header X-Forwarded-Host \$forwarded_host;" to "a host with its port is read by Tomcat without the port",
            "proxy_set_header X-Forwarded-Port \$forwarded_port;" to "a proxied location without the port makes the application rebuild the proof's URL on 443",
            "proxy_set_header X-Forwarded-Proto \$scheme;" to "the scheme is the edge's, never the client's",
            "proxy_set_header X-Forwarded-For \$remote_addr;" to "the address is replaced, not appended to",
            *listOf("Forwarded", "X-Forwarded-Prefix", "X-Forwarded-Ssl", "X-Forwarded-Server", "X-Forwarded-By", "X-Real-IP")
                .map { "proxy_set_header $it \"\";" to "$it is not read by the application, so what a client sends is dropped" }.toTypedArray(),
        ).forEach { (line, why) -> assertThat(lines.count { it == line }).describedAs(why).isEqualTo(proxied) }
        assertThat(lines.joinToString("\n")).contains("map \$http_host \$forwarded_port", "default 443;", "map \$http_host \$forwarded_host")
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

    private val pkiScript = Files.readString(Path.of("deploy/stack/pki.sh"))

    private fun pkiRoles(): Map<String, String> =
        Regex("""(?m)^\s+(edge|shortener|migrator|postgres)\)\s+cn=([a-z-]+);""").findAll(pkiScript).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `a service names a secret once, since a stack deploy keeps only the last of two mounts with one source`() {
        services.forEach { (name, service) ->
            val sources = service.list("secrets").map(::sourceOf)
            assertThat(sources).describedAs("secrets of $name").doesNotHaveDuplicates()
        }
    }

    @Test
    fun `the CA is on a network of its own, is not published, and its keys and passwords are its alone`() {
        val ca = services.getValue("stepca")
        val own = setOf("pki_ca_key", "pki_ca_pass", "pki_ca_json", "pki_provisioner_pass")

        assertThat(ca.list("ports")).isEmpty()
        assertThat(networksOf("stepca")).containsExactly("pki")
        assertThat(membersOf("pki")).containsExactly("stepca")
        assertThat(secretsOf(ca)).containsAll(own)
        services.filterKeys { it != "stepca" }.forEach { (name, service) ->
            assertThat(secretsOf(service)).describedAs("secrets of $name").doesNotContainAnyElementsOf(own)
        }
        ca.list("secrets").filterIsInstance<Map<*, *>>().filter { it["source"] in own }.forEach {
            assertThat(it["mode"]).describedAs("mode of ${it["source"]}").isEqualTo(256)
            assertThat(it["uid"].toString()).describedAs("owner of ${it["source"]}").isEqualTo("1000")
        }
        assertThat(ca.map("deploy").map("placement").list("constraints")).describedAs("the CA stays on the node that runs pki.sh")
            .containsExactly("node.labels.shortener.pki == true")
    }

    @Test
    fun `the CA's image is the one pki sh makes its configuration with`() {
        val overlay = Files.readString(Path.of("deploy/stack/overlays/compose.pki-ca.yaml"))

        val inOverlay = Regex("""\$\{STEPCA_IMAGE:-([^}]+)}""").find(overlay)!!.groupValues[1]
        val inScript = Regex("""STEPCA_IMAGE:-([^}]+)}""").find(pkiScript)!!.groupValues[1]
        assertThat(inScript).isEqualTo(inOverlay)
        assertThat(inOverlay).describedAs("pinned").matches("[^:]+:\\d+\\.\\d+\\.\\d+")
    }

    @Test
    fun `with the CA the application asks for the edge's certificate in TLS 1_3, serves the edge's identity alone, and keeps the management port plain`() {
        val application = environmentOf(services.getValue("shortener"))

        assertThat(application).containsEntry("SERVER_SSL_CLIENTAUTH", "need")
            .containsEntry("SPRING_SSL_BUNDLE_PEM_SERVER_OPTIONS_ENABLEDPROTOCOLS", "TLSv1.3")
            .describedAs("the management port shares the server's TLS settings, and the health check speaks plain HTTP")
            .containsEntry("MANAGEMENT_SERVER_SSL_ENABLED", "false")
        assertThat(application["SHORTENER_SECURITY_MTLS_ALLOWEDIDENTITIES"]).isEqualTo("spiffe://shortener.internal/prod/edge")
        assertThat(pkiScript).contains("domain=shortener.internal", "environment=prod", "spiffe://\$domain/\$environment/\$role")
        assertThat(application["SPRING_DATASOURCE_URL"]).contains("sslmode=verify-full", "sslrootcert=/run/secrets/pki_root", "sslkey=/run/secrets/pki_shortener_key_der")
        assertThat(environmentOf(services.getValue("migrate"))["SPRING_FLYWAY_URL"]).contains("sslmode=verify-full", "sslcert=/run/secrets/pki_migrator_cert")
    }

    @Test
    fun `a private key is held by the service that is that identity, and the DER keys are private to their user`() {
        mapOf(
            "pki_edge_key" to setOf("edge"),
            "pki_shortener_key" to setOf("shortener"),
            "pki_shortener_key_der" to setOf("shortener", "migrate"),
            "pki_migrator_key_der" to setOf("migrate"),
            "pki_postgres_key" to setOf("postgres"),
        ).forEach { (secret, holders) ->
            assertThat(services.filter { secret in secretsOf(it.value) }.keys).describedAs("who holds $secret").isEqualTo(holders)
        }
        services.values.flatMap { it.list("secrets").maps() }.filter { it.text("source").endsWith("_key_der") }.forEach {
            assertThat(it["mode"]).describedAs("pgjdbc refuses a key others can read: ${it["source"]}").isEqualTo(256)
            assertThat(it["uid"].toString()).isEqualTo("1000")
        }
    }

    @Test
    fun `the edge speaks TLS 1_3 to the application, verifies it and shows its own certificate, and plain HTTP stays the default`() {
        val tls = Files.readAllLines(Path.of("deploy/edge/upstream.mtls.conf")).filterNot { it.trimStart().startsWith("#") }.map { it.trim() }
        val plain = Files.readAllLines(Path.of("deploy/edge/upstream.conf")).filterNot { it.trimStart().startsWith("#") }.map { it.trim() }

        assertThat(plain).containsExactly("proxy_pass http://application;")
        assertThat(tls).contains(
            "proxy_pass https://application;", "proxy_ssl_protocols TLSv1.3;", "proxy_ssl_verify on;", "proxy_ssl_name shortener;",
            "proxy_ssl_certificate /run/secrets/mtls_cert;", "proxy_ssl_certificate_key /run/secrets/mtls_key;", "proxy_ssl_trusted_certificate /run/secrets/mtls_ca;",
        )
        assertThat(tls.count { it.startsWith("proxy_pass ") }).isEqualTo(1)
        val edge = services.getValue("edge").list("secrets").maps().filter { it.text("source").startsWith("pki_") }
        assertThat(edge.map { it["target"] }).containsExactlyInAnyOrder("mtls_cert", "mtls_key", "mtls_ca")
        assertThat(load("deploy/stack/compose.prod.yaml").map("configs").map("edge-upstream")["file"]).isEqualTo("../edge/upstream.conf")
        assertThat(load("deploy/stack/overlays/compose.pki.yaml").map("configs").map("edge-upstream")["file"]).isEqualTo("../edge/upstream.mtls.conf")
    }

    @Test
    fun `the database lets the application and the migration job in with a certificate alone, and maps its name to the role`() {
        val hba = Files.readAllLines(Path.of("deploy/postgres/pg_hba.mtls.conf")).filterNot { it.startsWith("#") || it.isBlank() }.map { it.trim().replace(Regex("\\s+"), " ") }
        val ident = Files.readAllLines(Path.of("deploy/postgres/pg_ident.mtls.conf")).filterNot { it.startsWith("#") || it.isBlank() }.map { it.trim().replace(Regex("\\s+"), " ") }

        assertThat(hba).containsSubsequence(
            "hostssl shortener shortener_app,shortener_migrator all cert map=pki",
            "host shortener shortener_app,shortener_migrator all reject",
            "host all all all scram-sha-256",
        )
        assertThat(ident).containsExactly("pki shortener-app shortener_app", "pki shortener-migrator shortener_migrator")
        assertThat(pkiRoles()).containsEntry("shortener", "shortener-app").containsEntry("migrator", "shortener-migrator")
        val command = services.getValue("postgres").list("command").joinToString("\n")
        assertThat(command).contains("install -m 0400 -o postgres -g postgres /run/secrets/pki_postgres_key", "ssl_min_protocol_version=TLSv1.3", "hba_file=/etc/postgresql/pg_hba.conf")
    }

    @Test
    fun `the database has an overlay of its own, which deploy sh deploys before the one of the services that call it`() {
        assertThat(load("deploy/stack/overlays/compose.pki-db.yaml").map("services").keys).containsExactly("postgres")
        assertThat(load("deploy/stack/overlays/compose.pki.yaml").map("services").keys).containsExactlyInAnyOrder("edge", "shortener", "migrate")
        val deploy = Files.readString(Path.of("deploy/stack/deploy.sh"))
        assertThat(deploy).containsSubsequence("compose.pki-db.yaml", "database_files=\$files", "compose.pki.yaml", "turning on mutual TLS: the database first", "deploy \"\$database_args\"")
        assertThat(deploy).describedAs("a rollback of the application to the one with no certificate is what that order avoids").contains("rolls the application back")
    }

    @Test
    fun `the names the CA signs for are the ones pki sh asks for, in its policy and in the constraints of the issuing certificate`() {
        val policy = Files.readString(Path.of("deploy/pki/ca-config"))
        val allowed = Regex("""allow: \{dns: \[([^]]*)]""").find(policy)!!.groupValues[1].split(",").map { it.trim().trim('"') }
        val constraints = Regex("""permitted;DNS:([a-z]+)""").findAll(pkiScript).map { it.groupValues[1] }.toList()
        val asked = Regex("""DNS:([a-z]+),""").findAll(pkiScript).map { it.groupValues[1] }.toSet()

        assertThat(allowed).contains("stepca").containsAll(pkiRoles().values).containsAll(constraints).containsAll(asked)
        assertThat(constraints).describedAs("what an identity may be named, and the CA itself").containsExactlyInAnyOrder("shortener", "postgres", "edge", "stepca")
        assertThat(policy).contains("dnsNames: [\"stepca\"]", "minVersion: 1.3")
    }

    @Test
    fun `every secret of the CA overlays is named after a hash of its contents, because a secret is immutable`() {
        listOf("deploy/stack/overlays/compose.pki-ca.yaml", "deploy/stack/overlays/compose.pki-db.yaml", "deploy/stack/overlays/compose.pki.yaml").forEach { path ->
            val secrets = load(path).map("secrets")
            assertThat(secrets).isNotEmpty
            secrets.forEach { (name, secret) -> assertThat(secret.asMap()["name"].toString()).describedAs("the name of secret $name in $path").contains("_HASH") }
        }
    }

    private val composePaths = listOf(
        "deploy/stack/compose.prod.yaml",
        "deploy/stack/overlays/compose.observability.yaml",
        "deploy/stack/overlays/compose.keycloak.yaml",
        "deploy/stack/overlays/compose.postgres-ha.yaml",
        "deploy/stack/overlays/compose.pki-ca.yaml",
        "deploy/stack/overlays/compose.pki-db.yaml",
        "deploy/stack/overlays/compose.pki.yaml",
    )

    @Test
    fun `every config is named after a hash of its file, because Swarm does not update one in place`() {
        (composePaths + "deploy/stack/overlays/compose.console.yaml").forEach { path ->
            val configs = load(path).map("configs")
            configs.forEach { (name, config) ->
                assertThat(config.asMap()["name"].toString()).describedAs("the name of config $name in $path").contains("_HASH")
            }
        }
    }

    @Test
    fun `the message of a required variable has none of the characters that the stack loader reads as an operator`() {
        (composePaths + "deploy/stack/overlays/compose.console.yaml").forEach { path ->
            Regex("""\$\{[A-Z][A-Z0-9_]*:\?([^}]*)}""").findAll(Files.readString(Path.of(path))).forEach {
                assertThat(it.groupValues[1])
                    .describedAs("docker stack deploy takes what follows a dash in the message of a required variable as its default, so a name built from it never changes: $path")
                    .doesNotContain("-", "+", "?")
            }
        }
    }

    @Test
    fun `every variable the files read is in dot env example, and every one listed there is read`() {
        val example = Files.readString(Path.of("deploy/stack/.env.example"))
        val listed = Regex("""(?m)^#?\s*([A-Z][A-Z0-9_]*)=""").findAll(example).map { it.groupValues[1] }.toSet()
        val read = composePaths
            .flatMap { Regex("""\$\{([A-Z][A-Z0-9_]*)""").findAll(Files.readString(Path.of(it))).map { match -> match.groupValues[1] }.toList() }
            .filterNot { it.endsWith("_HASH") || Regex("PKI_[A-Z0-9_]+_(SERIAL|SHA256|NOT_AFTER|RENEW_AFTER)").matches(it) }
            .toSet()

        assertThat(read - listed).describedAs("read by the files and missing from .env.example").isEmpty()
        assertThat(listed - read).describedAs("listed in .env.example and read by no file").isEmpty()
    }

    @Test
    fun `the defaults the stack gives the application's tunables are the application's own`() {
        val placeholder = Regex("""\$\{[A-Z0-9_]+:-([^}]*)}""")
        val defaults = environmentOf(services.getValue("shortener"))
            .mapNotNull { (name, value) -> placeholder.matchEntire(value)?.let { name to it.groupValues[1] } }
            .toMap()
        val binder = Binder.get(StandardEnvironment().apply { propertySources.addFirst(SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, defaults)) })

        assertThat(defaults.keys).contains(
            "SHORTENER_SECURITY_RATELIMIT_PERIP_CAPACITY", "SHORTENER_SECURITY_RATELIMIT_PERCLIENT_CAPACITY", "SERVER_TOMCAT_MAXCONNECTIONS",
            "SHORTENER_SHORTLINK_REDIRECTCACHE_ENABLED", "SHORTENER_SHORTLINK_REDIRECTCACHE_TTL",
            "SHORTENER_SHORTLINK_REDIRECTCACHE_MAXENTRIES", "SHORTENER_SHORTLINK_REDIRECTCACHE_STALEIFERROR",
        )
        assertThat(binder.bindOrCreate("shortener.security", SecurityProperties::class.java).rateLimit).isEqualTo(SecurityProperties().rateLimit)
        assertThat(binder.bindOrCreate("shortener.shortlink.redirect-cache", RedirectCacheProperties::class.java)).isEqualTo(RedirectCacheProperties())
        val tomcat = load("src/main/resources/application.yaml").map("server").map("tomcat")
        assertThat(defaults["SERVER_TOMCAT_MAXCONNECTIONS"]).isEqualTo(tomcat["max-connections"].toString())
    }

    @Test
    fun `the application's health check waits for a cold start on a small host before its failures count`() {
        val healthcheck = services.getValue("shortener").map("healthcheck")

        assertThat(healthcheck.text("start_period").removeSuffix("s").toInt()).describedAs("start_period in seconds").isGreaterThanOrEqualTo(45)
    }
}
