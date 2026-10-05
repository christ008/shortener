package uy.ct.shortener

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * `deploy/keycloak/DpopClient.java` is not part of the application, but the local setup, the smoke test of the native
 * image and the README all depend on it, and a call that threw before sending anything went unnoticed because the one
 * script that exercises it only runs on demand. This runs the real file as a subprocess, as the scripts do, against a
 * stand-in for Keycloak and the API, and checks what it sent: the proof is a valid signature by the key it
 * embeds, made for this method and URL and this token.
 */
class DpopClientTest {

    private class Request(val method: String, val uri: String, val headers: Map<String, String>, val body: String)

    private class TokenRequest(val headers: Map<String, String>, val form: Map<String, String>)

    private class Output(val exitCode: Int, val stdout: List<String>, val stderr: String)

    private val received = CopyOnWriteArrayList<Request>()
    private val tokenRequests = CopyOnWriteArrayList<TokenRequest>()
    private lateinit var server: HttpServer

    private val baseUrl get() = "http://localhost:${server.address.port}"

    @BeforeEach
    fun startStandIn() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/realms/shortener/protocol/openid-connect/token") { exchange ->
            val form = String(exchange.requestBody.readAllBytes()).split("&").associate {
                it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8)
            }
            tokenRequests += TokenRequest(exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.first() }, form)
            reply(exchange, 200, """{"access_token":"stand-in-token","token_type":"DPoP"}""")
        }
        server.createContext("/api") { exchange ->
            val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.first() }
            received += Request(exchange.requestMethod, exchange.requestURI.toString(), headers, String(exchange.requestBody.readAllBytes()))
            reply(exchange, 201, """{"shortCode":"abc"}""")
        }
        server.start()
    }

    @AfterEach
    fun stopStandIn() = server.stop(0)

    private fun reply(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun run(vararg args: String, environment: Map<String, String> = emptyMap()): Output {
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process = ProcessBuilder(listOf(java, "deploy/keycloak/DpopClient.java") + args)
            .apply {
                environment().remove("TRACEPARENT")
                environment()["TOKEN_URL"] = "$baseUrl/realms/shortener/protocol/openid-connect/token"
                environment().putAll(environment)
            }
            .start()
        val stderr = CompletableFuture.supplyAsync { process.errorReader().readText() }
        val stdout = process.inputReader().readLines()
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).describedAs("the client finished").isTrue()
        return Output(process.exitValue(), stdout, stderr.get())
    }

    private fun call(method: String, path: String, body: String? = null, environment: Map<String, String> = emptyMap()) =
        run(
            *listOfNotNull("call", "deploy/keycloak/dev-keys/demo-client.jwk.json", "demo-client", method, "$baseUrl$path", body).toTypedArray(),
            environment = environment,
        )

    @Test
    fun `calls the API with a DPoP token and a proof made for that request`() {
        val output = call("GET", "/api/short-links?size=1&sort=shortCode,asc")

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        assertThat(output.stdout.first()).isEqualTo("201")
        val request = received.single()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.headers["authorization"]).isEqualTo("DPoP stand-in-token")

        val proof = SignedJWT.parse(request.headers.getValue("dpop"))
        assertThat(proof.header.type.toString()).isEqualTo("dpop+jwt")
        assertThat(proof.verify(ECDSAVerifier(proof.header.jwk.toECKey()))).describedAs("signed by the key in its own header").isTrue()
        assertThat(proof.jwtClaimsSet.getStringClaim("htm")).isEqualTo("GET")
        assertThat(proof.jwtClaimsSet.getStringClaim("htu")).describedAs("without the query").isEqualTo("$baseUrl/api/short-links")
        val tokenHash = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest("stand-in-token".toByteArray()))
        assertThat(proof.jwtClaimsSet.getStringClaim("ath")).isEqualTo(tokenHash)
    }

    @Test
    fun `asks for the token with an assertion signed by the client key and a proof from the key the token is bound to`() {
        val output = call("GET", "/api/short-links")

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        val tokenRequest = tokenRequests.single()
        assertThat(tokenRequest.form).containsEntry("grant_type", "client_credentials").containsEntry("client_id", "demo-client")

        val assertion = SignedJWT.parse(tokenRequest.form.getValue("client_assertion"))
        val clientKey = ECKey.parse(Files.readString(Path.of("deploy/keycloak/dev-keys/demo-client.jwk.json"))).toPublicJWK()
        assertThat(assertion.verify(ECDSAVerifier(clientKey))).describedAs("signed by the client's own key").isTrue()
        assertThat(assertion.jwtClaimsSet.issuer).isEqualTo("demo-client")
        assertThat(assertion.jwtClaimsSet.subject).isEqualTo("demo-client")
        assertThat(assertion.jwtClaimsSet.audience).containsExactly("$baseUrl/realms/shortener")

        val tokenProof = SignedJWT.parse(tokenRequest.headers.getValue("dpop"))
        assertThat(tokenProof.jwtClaimsSet.getStringClaim("htm")).isEqualTo("POST")
        assertThat(tokenProof.jwtClaimsSet.getStringClaim("htu")).isEqualTo("$baseUrl/realms/shortener/protocol/openid-connect/token")
        assertThat(tokenProof.jwtClaimsSet.getClaim("ath")).describedAs("there is no token yet to hash").isNull()
        val apiProof = SignedJWT.parse(received.single().headers.getValue("dpop"))
        assertThat(apiProof.header.jwk.computeThumbprint()).isEqualTo(tokenProof.header.jwk.computeThumbprint())
    }

    @Test
    fun `sends no trace context and no content type when there is neither a TRACEPARENT nor a body`() {
        call("GET", "/api/short-links")

        val headers = received.single().headers
        assertThat(headers).doesNotContainKeys("traceparent", "content-type")
    }

    @Test
    fun `forwards TRACEPARENT as the trace context of the request`() {
        val traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

        val output = call("GET", "/api/short-links", environment = mapOf("TRACEPARENT" to traceparent))

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        assertThat(received.single().headers["traceparent"]).isEqualTo(traceparent)
    }

    @Test
    fun `sends a body as JSON`() {
        val body = """{"targetUrl":"https://example.com/x"}"""

        val output = call("POST", "/api/short-links", body)

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        val request = received.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.headers["content-type"]).isEqualTo("application/json")
        assertThat(request.body).isEqualTo(body)
    }

    @Test
    fun `explains its usage instead of a stack trace when it is given nothing`() {
        val output = run()

        assertThat(output.exitCode).isEqualTo(2)
        assertThat(output.stderr).contains("usage:").doesNotContain("Exception")
    }
}
