package uy.ct.shortener

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.json.JsonParserFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * `tools/Realms.java` makes the realm that `dev-setup` gives the development Keycloak and the one a deployment
 * imports, so a mistake in it is a Keycloak that trusts the wrong key or starts with a placeholder for a password. This runs
 * the real file as a subprocess, as the scripts do, on the JDK that runs the tests, which is the 25 the tools are written for,
 * and reads what it wrote as JSON.
 */
class RealmsTest {

    @TempDir
    lateinit var directory: Path

    private class Output(val exitCode: Int, val stdout: String, val stderr: String)

    private val java = System.getProperty("java.home") + "/bin/java"

    private fun run(vararg arguments: String, environment: Map<String, String> = emptyMap()): Output {
        val process = ProcessBuilder(java, "tools/Realms.java", *arguments)
            .directory(Path.of("").toAbsolutePath().toFile())
            .also { it.environment().putAll(environment) }
            .start()
        val stdout = process.inputStream.readAllBytes().decodeToString()
        val stderr = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(60, TimeUnit.SECONDS)) { "Realms.java did not finish" }
        return Output(process.exitValue(), stdout, stderr)
    }

    private fun publicKeyFile(clientId: String): Path {
        val key = ECKeyGenerator(Curve.P_256).keyID("$clientId-key-1").generate()
        return directory.resolve("$clientId.public.json").also { Files.writeString(it, key.toPublicJWK().toJSONString()) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(file: Path): Map<String, Any?> = JsonParserFactory.getJsonParser().parseMap(Files.readString(file)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.clients() = (this["clients"] as List<Map<String, Any?>>).associateBy { it["clientId"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.attributes() = this["attributes"] as Map<String, String>

    @Test
    fun `production makes a realm that trusts exactly the two public keys it was given`() {
        val demo = publicKeyFile("demo-client")
        val admin = publicKeyFile("admin-client")
        val realm = directory.resolve("realm.json")

        val result = run("production", demo.toString(), admin.toString(), realm.toString())

        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
        val clients = parse(realm).clients()
        assertThat(clients.keys).containsExactly("demo-client", "admin-client")
        assertThat(clients.getValue("demo-client").attributes()["jwks.string"]).isEqualTo("""{"keys":[${Files.readString(demo)}]}""")
        assertThat(clients.getValue("admin-client").attributes()["jwks.string"]).isEqualTo("""{"keys":[${Files.readString(admin)}]}""")
        assertThat(Files.readString(realm)).doesNotContain("@JWKS", "@PASSWORD").doesNotContain("\"d\"")
    }

    @Test
    fun `the production realm is for a public instance, with TLS, no users, no password flows and no stray clients`() {
        val realm = directory.resolve("realm.json")
        run("production", publicKeyFile("demo-client").toString(), publicKeyFile("admin-client").toString(), realm.toString())

        val parsed = parse(realm)
        assertThat(parsed["sslRequired"]).isEqualTo("external")
        assertThat(parsed["bruteForceProtected"]).isEqualTo(true)
        assertThat(parsed["registrationAllowed"]).isEqualTo(false)
        assertThat(parsed).doesNotContainKey("users")
        parsed.clients().values.forEach {
            assertThat(it["directAccessGrantsEnabled"]).describedAs("${it["clientId"]} password grant").isEqualTo(false)
            assertThat(it["standardFlowEnabled"]).describedAs("${it["clientId"]} browser flow").isEqualTo(false)
            assertThat(it["clientAuthenticatorType"]).isEqualTo("client-jwt")
        }
        assertThat(parsed.clients().getValue("demo-client")["defaultClientScopes"]).isEqualTo(listOf("shortlinks:create", "shortlinks:read", "shortlinks:delete"))
        assertThat(parsed.clients().getValue("admin-client")["defaultClientScopes"]).isEqualTo(listOf("shortlinks:admin"))
    }

    @Test
    fun `a private key is refused, whichever file it is in, and nothing is written`() {
        val private = directory.resolve("private.json").also { Files.writeString(it, ECKeyGenerator(Curve.P_256).generate().toJSONString()) }
        val realm = directory.resolve("realm.json")

        val result = run("production", private.toString(), publicKeyFile("admin-client").toString(), realm.toString())

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("holds a private key")
        assertThat(realm).doesNotExist()
    }

    @Test
    fun `render fills the development template, and a password with quotes and backslashes survives as itself`() {
        val keys = listOf("demo-client", "other-client", "admin-client", "no-scope-client").flatMap { listOf("--jwks", "$it=${publicKeyFile(it)}") }
        val realm = directory.resolve("dev.json")
        val awkward = """pa"ss\word 'x'"""

        val result = run(
            "render", "deploy/keycloak/shortener-realm.template.json", realm.toString(), *keys.toTypedArray(),
            "--password", "alice=ALICE", "--password", "bob=BOB",
            environment = mapOf("ALICE" to awkward, "BOB" to "plain"),
        )

        assertThat(result.exitCode).describedAs(result.stderr).isEqualTo(0)
        @Suppress("UNCHECKED_CAST")
        val users = (parse(realm)["users"] as List<Map<String, Any?>>).associateBy { it["username"] as String }
        @Suppress("UNCHECKED_CAST")
        fun password(user: String) = ((users.getValue(user)["credentials"] as List<Map<String, Any?>>).single()["value"])
        assertThat(password("alice")).isEqualTo(awkward)
        assertThat(password("bob")).isEqualTo("plain")
    }

    @Test
    fun `a placeholder with no value, and a value with no placeholder, are both errors`() {
        val realm = directory.resolve("realm.json")
        val template = "deploy/keycloak/shortener-realm.production.template.json"

        val missing = run("render", template, realm.toString(), "--jwks", "demo-client=${publicKeyFile("demo-client")}")
        val extra = run(
            "render", template, realm.toString(), "--jwks", "demo-client=${publicKeyFile("demo-client")}",
            "--jwks", "admin-client=${publicKeyFile("admin-client")}", "--jwks", "typo-client=${publicKeyFile("typo-client")}",
        )

        assertThat(missing.exitCode).isEqualTo(1)
        assertThat(missing.stderr).contains("@JWKS:admin-client@")
        assertThat(extra.exitCode).isEqualTo(1)
        assertThat(extra.stderr).contains("@JWKS:typo-client@")
        assertThat(realm).doesNotExist()
    }

    @Test
    fun `a password variable that is not set is an error, not an empty password`() {
        val result = run(
            "render", "deploy/keycloak/shortener-realm.template.json", directory.resolve("dev.json").toString(),
            "--password", "alice=NOT_SET_ANYWHERE",
        )

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.stderr).contains("NOT_SET_ANYWHERE")
    }

    @Test
    fun `arguments it does not understand end with usage and status 2`() {
        assertThat(run().exitCode).isEqualTo(2)
        assertThat(run("nonsense").exitCode).isEqualTo(2)
        assertThat(run("render", "only-a-template").exitCode).isEqualTo(2)
        assertThat(run("production", "one").stderr).contains("usage")
    }

    @Test
    fun `the realm file is readable by the container that imports it`() {
        val realm = directory.resolve("realm.json")
        run("production", publicKeyFile("demo-client").toString(), publicKeyFile("admin-client").toString(), realm.toString())

        assertThat(Files.getPosixFilePermissions(realm).map { it.name }).containsExactlyInAnyOrder("OWNER_READ", "OWNER_WRITE", "GROUP_READ", "OTHERS_READ")
    }

    @Test
    fun `the tools compile for JDK 25 with every lint on, together, as the launcher compiles them`() {
        val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        val diagnostics = javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>()
        val output = Files.createDirectory(directory.resolve("classes"))
        val task = compiler.getTask(
            null, null, diagnostics, listOf("--release", "25", "-Xlint:all", "-Werror", "-d", output.toString()), null,
            compiler.getStandardFileManager(diagnostics, null, null).getJavaFileObjects(*Files.list(Path.of("tools")).use { it.filter { f -> f.toString().endsWith(".java") }.toList() }.toTypedArray()),
        )

        assertThat(task.call()).describedAs(diagnostics.diagnostics.joinToString("\n")).isTrue()
    }
}
