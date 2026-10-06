package uy.ct.shortener

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
 * `deploy/keycloak/dpop` is not part of the application, but the local setup, the smoke test of the native image and the
 * README all depend on it, and a call that failed before sending anything went unnoticed because the one script that
 * exercises it only runs on demand. This runs the real script as a subprocess, as the other scripts do, against a
 * stand-in for Keycloak and the API, and checks what it sent with a different implementation (Nimbus): the proof is a
 * valid signature by the key it embeds, made for this method and URL and this token, and the client assertion verifies
 * against the client's public key.
 *
 * It signs with openssl and builds the keys and signatures by hand, so the tests also cover the places that go wrong in
 * such code: a key made by another implementation, keys of its own making, signatures whose integers are shorter or
 * longer than 32 bytes, and many runs in a row.
 */
class DpopClientTest {

    @TempDir
    lateinit var directory: Path

    private lateinit var clientKey: ECKey

    private lateinit var keyFile: Path

    private class Request(val method: String, val uri: String, val headers: Map<String, String>, val body: String)

    private class TokenRequest(val headers: Map<String, String>, val form: Map<String, String>)

    private class Output(val exitCode: Int, val stdout: List<String>, val stderr: String)

    private val received = CopyOnWriteArrayList<Request>()
    private val tokenRequests = CopyOnWriteArrayList<TokenRequest>()
    private lateinit var server: HttpServer

    private val baseUrl get() = "http://localhost:${server.address.port}"

    @BeforeEach
    fun startStandIn() {
        clientKey = ECKeyGenerator(Curve.P_256).keyID("demo-client-key-1").generate()
        keyFile = directory.resolve("demo-client.jwk.json").also { Files.writeString(it, clientKey.toJSONString()) }
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
        val process = ProcessBuilder(listOf("sh", "deploy/keycloak/dpop") + args)
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

    private fun call(method: String, path: String, body: String? = null, environment: Map<String, String> = emptyMap(), key: Path = keyFile) =
        run(
            *listOfNotNull("call", key.toString(), "demo-client", method, "$baseUrl$path", body).toTypedArray(),
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
        assertThat(assertion.verify(ECDSAVerifier(clientKey.toPublicJWK()))).describedAs("signed by the client's own key").isTrue()
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

    @Test
    fun `prints just the token`() {
        val output = run("token", keyFile.toString(), "demo-client")

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        assertThat(output.stdout).containsExactly("stand-in-token")
    }

    @Test
    fun `makes keys of its own that another implementation reads, and that sign for the client`() {
        val output = run("keygen", "fresh-client")

        assertThat(output.exitCode).describedAs(output.stderr).isZero()
        assertThat(output.stdout).hasSize(2)
        val private = ECKey.parse(output.stdout[0])
        val public = ECKey.parse(output.stdout[1])
        assertThat(private.isPrivate).isTrue
        assertThat(public.isPrivate).isFalse
        assertThat(private.keyID).isEqualTo("fresh-client-key-1")
        assertThat(private.toPublicJWK().computeThumbprint()).describedAs("the public half is the private key's").isEqualTo(public.computeThumbprint())
        assertThat(private.d.decode()).describedAs("a 32 byte scalar").hasSize(32)

        val file = directory.resolve("fresh.jwk.json").also { Files.writeString(it, output.stdout[0]) }
        val used = call("GET", "/api/short-links", key = file)

        assertThat(used.exitCode).describedAs(used.stderr).isZero()
        val assertion = SignedJWT.parse(tokenRequests.single().form.getValue("client_assertion"))
        assertThat(assertion.verify(ECDSAVerifier(public))).describedAs("signed by the key that keygen made").isTrue()
    }

    @Test
    fun `keeps signing correctly over many runs, whatever the shape of the signature`() {
        repeat(15) {
            val output = run("token", keyFile.toString(), "demo-client")

            assertThat(output.exitCode).describedAs("run $it: ${output.stderr}").isZero()
        }

        assertThat(tokenRequests).hasSize(15)
        tokenRequests.forEach { request ->
            val assertion = SignedJWT.parse(request.form.getValue("client_assertion"))
            val proof = SignedJWT.parse(request.headers.getValue("dpop"))
            assertThat(assertion.verify(ECDSAVerifier(clientKey.toPublicJWK()))).isTrue
            assertThat(proof.verify(ECDSAVerifier(proof.header.jwk.toECKey()))).isTrue
        }
        assertThat(tokenRequests.map { it.form.getValue("client_assertion") }).describedAs("a new assertion every time").doesNotHaveDuplicates()
    }

    @Test
    fun `turns the DER integers of a signature into the 32 byte halves of ES256, whatever their length`() {
        fun der(r: ByteArray, s: ByteArray): ByteArray {
            val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
            return byteArrayOf(0x30, body.size.toByte()) + body
        }

        fun escapes(bytes: ByteArray) = bytes.joinToString("") { "\\0%03o".format(it.toInt() and 0xff) }

        val high = ByteArray(32) { (0x80 + it).toByte() }
        val low = ByteArray(32) { (it + 1).toByte() }
        val cases = mapOf(
            "both 32 bytes" to (low to low),
            "r with the leading zero DER adds for a high bit" to (byteArrayOf(0) + high to low),
            "r one byte short" to (low.copyOfRange(1, 32) to low),
            "s of a single byte" to (low to byteArrayOf(5)),
            "both short" to (low.copyOfRange(2, 32) to low.copyOfRange(1, 32)),
        )
        val realOpenssl = ProcessBuilder("sh", "-c", "command -v openssl").start().inputReader().readText().trim()
        val fakeBin = directory.resolve("bin").also(Files::createDirectories)
        val fake = fakeBin.resolve("openssl")
        Files.writeString(
            fake,
            "#!/bin/sh\nif [ \"\$1\" = dgst ]; then cat >/dev/null; printf '%b' \"\$FAKE_DER\"; exit 0; fi\nexec $realOpenssl \"\$@\"\n",
        )
        fake.toFile().setExecutable(true)

        cases.forEach { (name, pair) ->
            val (r, s) = pair
            val process = ProcessBuilder("sh", "-c", ". deploy/keycloak/dpop; sign_es256 unused input")
                .apply {
                    environment()["DPOP_AS_LIBRARY"] = "1"
                    environment()["PATH"] = "$fakeBin:" + environment()["PATH"]
                    environment()["FAKE_DER"] = escapes(der(r, s))
                }
                .start()
            val signature = process.inputReader().readText().trim()
            process.waitFor(30, TimeUnit.SECONDS)
            val raw = Base64.getUrlDecoder().decode(signature)

            val padded = fun(value: ByteArray): ByteArray {
                val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
                return ByteArray(32 - trimmed.size) + trimmed
            }
            assertThat(raw).describedAs(name).hasSize(64)
            assertThat(raw.copyOfRange(0, 32)).describedAs("$name: r").isEqualTo(padded(r))
            assertThat(raw.copyOfRange(32, 64)).describedAs("$name: s").isEqualTo(padded(s))
        }
    }
}
