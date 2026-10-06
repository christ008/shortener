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
import java.util.concurrent.TimeUnit

/**
 * `Smoke` is what decides that a release can be published, so what matters is that it passes against an application that
 * answers as the API says, that one wrong answer fails the run and says which, and that an application that is not there fails
 * every check instead of ending the run with a stack trace. The application is a small stand-in that models the API's rules for
 * who may see what. Smoke runs as a process, as `tools/run` starts it, because the DPoP client it calls reads its token endpoint
 * from the environment of the process.
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

    private fun smoke(base: String = application.url, management: String = application.url): Output {
        val process = ProcessBuilder(
            ProcessHandle.current().info().command().orElse("java"), "-cp", System.getProperty("java.class.path"),
            "uy.ct.shortener.tools.MainKt", "Smoke", base,
        ).directory(repositoryRoot.toFile()).also {
            it.environment().apply {
                put("TOKEN_URL", "${application.url}/realms/shortener/protocol/openid-connect/token")
                put("KEYS_DIR", directory.resolve("keys").toString())
                put("MGMT", management)
            }
        }.start()
        val stdout = process.inputStream.readAllBytes().decodeToString()
        val stderr = process.errorStream.readAllBytes().decodeToString()
        check(process.waitFor(120, TimeUnit.SECONDS)) { "Smoke did not finish" }
        return Output(process.exitValue(), stdout, stderr)
    }

    private fun Output.lines(prefix: String) = stdout.lines().filter { it.startsWith(prefix) }

    @Test
    fun `passes against an application that answers as the API says`() {
        val result = smoke()

        assertThat(result.status).describedAs(result.stdout + result.stderr).isEqualTo(0)
        assertThat(result.stdout).contains("all checks passed")
        assertThat(result.lines("FAIL")).isEmpty()
        assertThat(result.lines("ok    ")).hasSize(21).contains("ok    create with a generated code", "ok    owner disables", "ok    disabled link answers gone")
    }

    @Test
    fun `a wrong answer fails the run and says which check and what it got, and the other checks still run`() {
        application.acceptsATakenCustomCode = true

        val result = smoke()

        assertThat(result.status).isEqualTo(1)
        assertThat(result.lines("FAIL")).containsExactly("FAIL  custom code taken (expected 409, got 201)")
        assertThat(result.stdout).contains("1 checks failed").doesNotContain("all checks passed")
        assertThat(result.lines("ok    ")).hasSize(20)
    }

    @Test
    fun `an application that is not there fails every check instead of ending the run`() {
        val unreachable = StandInApplication().use { it.url }

        val result = smoke(base = unreachable, management = unreachable)

        assertThat(result.status).isEqualTo(1)
        assertThat(result.stderr).doesNotContain("Exception in thread")
        assertThat(result.lines("FAIL")).hasSize(21)
        assertThat(result.stdout).contains("21 checks failed")
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
        var acceptsATakenCustomCode = false

        private class Link(val owner: String, var disabled: Boolean = false)

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
            val client = authorization?.takeIf { it.startsWith("DPoP ") }?.removePrefix("DPoP ")

            when {
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
                method == "POST" -> create(exchange, body, client)
                method == "GET" && code.isEmpty() -> reply(exchange, if (query?.contains("sort=targetUrl") == true) 400 else 200, "{}")
                method == "GET" -> if (mayManage) reply(exchange, 200, "{}") else reply(exchange, 404)
                method == "DELETE" -> if (link != null && link.owner == client) { link.disabled = true; reply(exchange, 204) } else reply(exchange, 404)
                else -> reply(exchange, 405)
            }
        }

        private fun create(exchange: HttpExchange, body: String, client: String) {
            if (!body.contains("\"targetUrl\":\"https://")) return reply(exchange, 400)
            val custom = Regex("\"customCode\":\"([^\"]+)\"").find(body)?.groupValues?.get(1)
            if (custom != null && custom in links && !acceptsATakenCustomCode) return reply(exchange, 409)
            val code = custom ?: "gen${links.size}xyz"
            links[code] = Link(client)
            reply(exchange, 201, """{"shortCode":"$code"}""")
        }
    }
}
