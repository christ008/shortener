package uy.ct.shortener.tools

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * `Realms` makes the realm that `dev-setup` gives the development Keycloak and the one a deployment imports, so a mistake in it
 * is a Keycloak that trusts the wrong key or starts with a placeholder for a password. It runs here as the launcher runs it, in
 * process, against the real templates, and what it wrote is read back as JSON.
 */
class RealmsTest {

    @TempDir
    lateinit var directory: Path

    private fun publicKeyFile(clientId: String): Path {
        val key = ECKeyGenerator(Curve.P_256).keyID("$clientId-key-1").generate()
        return directory.resolve("$clientId.public.json").also { Files.writeString(it, key.toPublicJWK().toJSONString()) }
    }

    private fun parse(file: Path): Map<String, Any?> = parseJson(Files.readString(file))

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.clients() = (this["clients"] as List<Map<String, Any?>>).associateBy { it["clientId"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.attributes() = this["attributes"] as Map<String, String>

    @Test
    fun `production makes a realm that trusts exactly the two public keys it was given`() {
        val demo = publicKeyFile("demo-client")
        val admin = publicKeyFile("admin-client")
        val realm = directory.resolve("realm.json")

        val result = run(Realms, "production", demo.toString(), admin.toString(), realm.toString())

        assertThat(result.status).describedAs(result.stderr).isEqualTo(0)
        val clients = parse(realm).clients()
        assertThat(clients.keys).containsExactly("demo-client", "admin-client")
        assertThat(clients.getValue("demo-client").attributes()["jwks.string"]).isEqualTo("""{"keys":[${Files.readString(demo)}]}""")
        assertThat(clients.getValue("admin-client").attributes()["jwks.string"]).isEqualTo("""{"keys":[${Files.readString(admin)}]}""")
        assertThat(Files.readString(realm)).doesNotContain("@JWKS", "@PASSWORD").doesNotContain("\"d\"")
    }

    @Test
    fun `the production realm is for a public instance, with TLS, no users, no password flows and no stray clients`() {
        val realm = directory.resolve("realm.json")
        run(Realms, "production", publicKeyFile("demo-client").toString(), publicKeyFile("admin-client").toString(), realm.toString())

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

        val result = run(Realms, "production", private.toString(), publicKeyFile("admin-client").toString(), realm.toString())

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("holds a private key")
        assertThat(realm).doesNotExist()
    }

    @Test
    fun `render fills the development template, and a password with quotes and backslashes survives as itself`() {
        val keys = listOf("demo-client", "other-client", "admin-client", "no-scope-client").flatMap { listOf("--jwks", "$it=${publicKeyFile(it)}") }
        val realm = directory.resolve("dev.json")
        val awkward = """pa"ss\word 'x'"""

        val result = run(
            Realms, "render", "deploy/keycloak/shortener-realm.template.json", realm.toString(), *keys.toTypedArray(),
            "--password", "alice=ALICE", "--password", "bob=BOB",
            environment = mapOf("ALICE" to awkward, "BOB" to "plain"),
        )

        assertThat(result.status).describedAs(result.stderr).isEqualTo(0)
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

        val missing = run(Realms, "render", template, realm.toString(), "--jwks", "demo-client=${publicKeyFile("demo-client")}")
        val extra = run(
            Realms, "render", template, realm.toString(), "--jwks", "demo-client=${publicKeyFile("demo-client")}",
            "--jwks", "admin-client=${publicKeyFile("admin-client")}", "--jwks", "typo-client=${publicKeyFile("typo-client")}",
        )

        assertThat(missing.status).isEqualTo(1)
        assertThat(missing.stderr).contains("@JWKS:admin-client@")
        assertThat(extra.status).isEqualTo(1)
        assertThat(extra.stderr).contains("@JWKS:typo-client@")
        assertThat(realm).doesNotExist()
    }

    @Test
    fun `a password variable that is not set is an error, not an empty password`() {
        val result = run(
            Realms, "render", "deploy/keycloak/shortener-realm.template.json", directory.resolve("dev.json").toString(),
            "--password", "alice=NOT_SET_ANYWHERE",
        )

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).contains("NOT_SET_ANYWHERE")
    }

    @Test
    fun `arguments it does not understand end with usage and status 2`() {
        assertThat(run(Realms).status).isEqualTo(2)
        assertThat(run(Realms, "nonsense").status).isEqualTo(2)
        assertThat(run(Realms, "render", "only-a-template").status).isEqualTo(2)
        assertThat(run(Realms, "production", "one").stderr).contains("usage")
    }

    @Test
    fun `the realm file is readable by the container that imports it`() {
        val realm = directory.resolve("realm.json")
        run(Realms, "production", publicKeyFile("demo-client").toString(), publicKeyFile("admin-client").toString(), realm.toString())

        assertThat(Files.getPosixFilePermissions(realm).map { it.name }).containsExactlyInAnyOrder("OWNER_READ", "OWNER_WRITE", "GROUP_READ", "OTHERS_READ")
    }
}
