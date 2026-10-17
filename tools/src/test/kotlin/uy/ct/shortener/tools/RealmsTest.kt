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

    private fun Map<String, Any?>.clients() = list("clients").byText("clientId")

    private fun Map<String, Any?>.attributes() = map("attributes")

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
        assertThat(parsed.clients().getValue("demo-client")["defaultClientScopes"])
            .isEqualTo(listOf("shortlinks:create", "shortlinks:read", "shortlinks:delete", "shortener-owner"))
        assertThat(parsed.clients().getValue("admin-client")["defaultClientScopes"]).isEqualTo(listOf("shortlinks:admin", "shortener-owner"))
    }

    private fun Map<String, Any?>.registrationPolicies(subType: String) =
        map("components").list("org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy")
            .map { it.asMap() }.filter { it["subType"] == subType }.associateBy { it.text("name") }

    @Test
    fun `the step-up fragment is a flow that asks for a one-time code at level 2, with every flow and configuration it names, and no name for a level`() {
        val fragment = parse(repositoryRoot.resolve("deploy/keycloak/realm-step-up.json"))

        val flows = fragment.list("authenticationFlows").byText("alias")
        val configs = fragment.list("authenticatorConfig").byText("alias")
        val executions = flows.values.flatMap { flow -> flow.list("authenticationExecutions").map { it.asMap() } }

        assertThat(fragment["browserFlow"]).isEqualTo("browser-loa")
        assertThat(flows.keys).contains("browser-loa")
        assertThat(executions.mapNotNull { it["flowAlias"]?.toString() }).describedAs("every sub-flow it names exists").allSatisfy { assertThat(flows).containsKey(it) }
        assertThat(executions.mapNotNull { it["authenticatorConfig"]?.toString() }).describedAs("every configuration it names exists").allSatisfy { assertThat(configs).containsKey(it) }
        val levels = configs.mapValues { it.value.map("config")["loa-condition-level"] }
        assertThat(levels).containsEntry("loa1", "1").containsEntry("loa2", "2")
        val level2 = flows.getValue("browser-loa level 2")
        assertThat(level2.list("authenticationExecutions").map { it.asMap()["authenticator"] }).contains("auth-otp-form")
        assertThat(fragment).describedAs("levels stay numbers: a name such as pwd would label a client that signs in with a key").doesNotContainKey("attributes")
    }

    @Test
    fun `a client that registers itself gets create, read and delete, owns what it makes and cannot choose what it is given`() {
        val realm = directory.resolve("realm.json")
        run(Realms, "production", publicKeyFile("demo-client").toString(), publicKeyFile("admin-client").toString(), realm.toString())
        val parsed = parse(realm)

        val scopes = parsed.list("clientScopes").byText("name")
        assertThat(parsed["defaultDefaultClientScopes"])
            .describedAs("what a client registered by itself starts with: never claim, which picks a code, nor admin")
            .isEqualTo(listOf("shortener-owner", "shortlinks:create", "shortlinks:read", "shortlinks:delete"))
        val owner = scopes.getValue("shortener-owner").list("protocolMappers").single().asMap()
        val config = owner.map("config")
        assertThat(owner["protocolMapper"]).describedAs("the owner is written by the provider from the client id of the session").isEqualTo("oidc-usersessionmodel-note-mapper")
        assertThat(config).containsEntry("user.session.note", "client_id").containsEntry("claim.name", "owner")
        assertThat(Files.readString(realm)).describedAs("no client carries a fixed owner of its own").doesNotContain("oidc-hardcoded-claim-mapper")

        listOf("anonymous", "authenticated").forEach { subType ->
            val policies = parsed.registrationPolicies(subType)
            val mappers = policies.getValue("Allowed Protocol Mapper Types").map("config").list("allowed-protocol-mapper-types")
            assertThat(mappers).describedAs("$subType: a registrant adds no mapper").isEmpty()
            assertThat(policies.getValue("Allowed Client Scopes")["providerId"]).describedAs("$subType: scopes beyond the defaults are refused").isEqualTo("allowed-client-templates")
            assertThat(policies.getValue("Max Clients Limit").map("config").list("max-clients")).describedAs("$subType: the invitations are limited").containsExactly("50")
        }
        assertThat(parsed.registrationPolicies("anonymous").getValue("Trusted Hosts").map("config")).describedAs("nobody is a trusted host, so no registration without an invitation")
            .doesNotContainKey("trusted-hosts")
        assertThat(parsed["registrationAllowed"]).isEqualTo(false)
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
        val users = parse(realm).list("users").byText("username")
        fun password(user: String) = users.getValue(user).list("credentials").single().asMap()["value"]
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
