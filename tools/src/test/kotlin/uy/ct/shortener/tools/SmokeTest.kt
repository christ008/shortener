package uy.ct.shortener.tools

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

/**
 * `Smoke` is what decides that a release can be published, so what matters is that it passes against an application that
 * answers as the API says, that one wrong answer fails the run and says which, and that an application that is not there fails
 * every check instead of ending the run with a stack trace. The application is a small stand-in that models the API's rules for
 * who may see what.
 */
class SmokeTest {

    @TempDir
    lateinit var directory: Path

    private lateinit var application: StandInApplication

    @BeforeEach
    fun start() {
        application = StandInApplication()
        Files.createDirectories(directory.resolve("keys"))
        listOf("demo-client", "other-client", "admin-client", "no-scope-client").forEach {
            Files.writeString(directory.resolve("keys/$it.jwk.json"), ECKeyGenerator(Curve.P_256).keyID("$it-key-1").generate().toJSONString())
        }
    }

    @AfterEach
    fun stop() = application.close()

    private fun smoke(base: String = application.url, management: String = application.url): Output =
        run(
            Smoke, base,
            environment = mapOf(
                "TOKEN_URL" to "${application.url}/realms/shortener/protocol/openid-connect/token",
                "KEYS_DIR" to directory.resolve("keys").toString(),
                "MGMT" to management,
            ),
        )

    private fun Output.lines(prefix: String) = stdout.lines().filter { it.startsWith(prefix) }

    @Test
    fun `passes against an application that answers as the API says`() {
        val result = smoke()

        assertThat(result.status).describedAs(result.stdout + result.stderr).isEqualTo(0)
        assertThat(result.stdout).contains("all checks passed")
        assertThat(result.lines("FAIL")).isEmpty()
        assertThat(result.lines("ok    ")).hasSize(26).contains("ok    create with a generated code", "ok    owner disables", "ok    disabling again is harmless", "ok    a link cannot be deleted", "ok    disabled link answers gone")
    }

    @Test
    fun `a wrong answer fails the run and says which check and what it got, and the other checks still run`() {
        application.acceptsAClaimForAnotherTarget = true

        val result = smoke()

        assertThat(result.status).isEqualTo(1)
        assertThat(result.lines("FAIL")).containsExactly("FAIL  claiming it for another target is refused (expected 409, got 201)")
        assertThat(result.stdout).contains("1 checks failed").doesNotContain("all checks passed")
        assertThat(result.lines("ok    ")).hasSize(25)
    }

    @Test
    fun `an application that is not there fails every check instead of ending the run`() {
        val unreachable = StandInApplication().use { it.url }

        val result = smoke(base = unreachable, management = unreachable)

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).isEmpty()
        assertThat(result.lines("FAIL")).hasSize(26)
        assertThat(result.stdout).contains("26 checks failed")
    }

    @Test
    fun `more than one argument is a usage error`() {
        val result = run(Smoke, "http://a", "http://b")

        assertThat(result.status).isEqualTo(2)
        assertThat(result.stderr).contains("at most one argument", "usage:")
    }

    /**
     * The API as far as the checks see it: a bearer or missing credential is 401, a client without scopes 403, a link belongs to
     * its creator and looks missing to others, an administrator sees any, a disabled link is gone, and the public paths answer.
     */
    private class StandInApplication : AutoCloseable {
        var acceptsAClaimForAnotherTarget = false

        private class Link(val owner: String, val target: String = "", var disabled: Boolean = false)

        private val links = mutableMapOf<String, Link>()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

        val url get() = "http://localhost:${server.address.port}"

        init {
            server.createContext("/") { exchange -> exchange.use(::handle) }
            server.start()
        }

        override fun close() = server.stop(0)

        private fun reply(exchange: HttpExchange, status: Int, body: String = "", location: String? = null) {
            location?.let { exchange.responseHeaders.add("Location", it) }
            val bytes = body.toByteArray()
            // 204 and 302 answer with no body, which the server wants as a length of -1.
            exchange.sendResponseHeaders(status, if (status == 204 || status == 302) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty() && status != 204 && status != 302) exchange.responseBody.write(bytes)
        }

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod
            val body = exchange.requestBody.readAllBytes().decodeToString()
            val authorization = exchange.requestHeaders.getFirst("Authorization")
            val proved = exchange.requestHeaders.getFirst("DPoP") != null
            val client = authorization?.takeIf { it.startsWith("DPoP ") && proved }?.removePrefix("DPoP ")

            when {
                path == "/realms/shortener/protocol/openid-connect/token" && !proved -> reply(exchange, 400, """{"error":"invalid_dpop_proof"}""")
                path == "/realms/shortener/protocol/openid-connect/token" ->
                    reply(exchange, 200, """{"access_token":"${Regex("client_id=([^&]+)").find(body)!!.groupValues[1]}","token_type":"DPoP"}""")
                path == "/.well-known/oauth-protected-resource" || path == "/actuator/health/readiness" -> reply(exchange, 200, "{}")
                path == "/actuator/prometheus" -> reply(exchange, 200, "# HELP x\nhttp_server_requests_seconds_bucket{le=\"0.1\"} 1\n")
                path.startsWith("/api/short-links") -> if (client == null) reply(exchange, 401) else api(exchange, method, path, exchange.requestURI.query, body, client)
                method == "GET" -> redirect(exchange, path.removePrefix("/"))
                else -> reply(exchange, 404)
            }
        }

        private fun redirect(exchange: HttpExchange, code: String) {
            val link = links[code]
            when {
                link == null -> reply(exchange, 404)
                link.disabled -> reply(exchange, 410)
                else -> reply(exchange, 302, location = "https://example.com/")
            }
        }

        private fun api(exchange: HttpExchange, method: String, path: String, query: String?, body: String, client: String) {
            val code = path.removePrefix("/api/short-links").removePrefix("/")
            val link = links[code]
            val mayManage = link != null && (link.owner == client || client == "admin-client")
            when {
                method == "POST" && client == "no-scope-client" -> reply(exchange, 403)
                method == "PUT" && client != "demo-client" -> reply(exchange, 403)
                method == "PUT" -> claim(exchange, code, body, client)
                method == "POST" -> create(exchange, body, client)
                method == "GET" && code.isEmpty() -> reply(exchange, if (query?.contains("sort=targetUrl") == true) 400 else 200, "{}")
                method == "GET" -> if (mayManage) reply(exchange, 200, "{}") else reply(exchange, 404)
                method == "PATCH" -> if (link != null && link.owner == client && body.contains("\"disabled\":true")) { link.disabled = true; reply(exchange, 200, """{"shortCode":"$code"}""") } else reply(exchange, 404)
                else -> reply(exchange, 405)
            }
        }

        private fun create(exchange: HttpExchange, body: String, client: String) {
            if (!body.contains("\"targetUrl\":\"https://") || body.contains("customCode")) return reply(exchange, 400)
            val code = "gen${links.size}xyz"
            links[code] = Link(client)
            reply(exchange, 201, """{"shortCode":"$code"}""")
        }

        /** A claim makes the link, repeats it harmlessly for the same target, and refuses any other. */
        private fun claim(exchange: HttpExchange, code: String, body: String, client: String) {
            val target = Regex("\"targetUrl\":\"(https://[^\"]+)\"").find(body)?.groupValues?.get(1) ?: return reply(exchange, 400)
            val existing = links[code]
            when {
                existing == null -> { links[code] = Link(client, target); reply(exchange, 201, """{"shortCode":"$code"}""") }
                existing.owner == client && existing.target == target -> reply(exchange, 200, """{"shortCode":"$code"}""")
                acceptsAClaimForAnotherTarget -> reply(exchange, 201, """{"shortCode":"$code"}""")
                else -> reply(exchange, 409)
            }
        }
    }
}
