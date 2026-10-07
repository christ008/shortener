package uy.ct.shortener.tools

import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * `DpopCalls` is the client the tools sign in with, so what it sends is checked as the identity provider and the API would check
 * it, with a library that did not write it: the assertion that authenticates the client is signed by the client's key, the proof
 * of each request by a key of its own that goes in its header, and the token is bound to that key by the proof of the call. The
 * identity provider and the API are one stand-in that keeps what it was sent.
 */
class DpopCallsTest {

    @TempDir
    lateinit var directory: Path

    private class Received(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    private lateinit var server: HttpServer
    private val received = mutableListOf<Received>()
    private var tokenStatus = 200
    private var tokenBody = """{"access_token":"the-access-token","token_type":"DPoP"}"""
    private lateinit var clientKey: ECKey
    private lateinit var keyFile: Path

    private val base get() = "http://localhost:${server.address.port}"
    private val calls get() = DpopCalls("$base/realms/shortener${DpopCalls.TOKEN_ENDPOINT_PATH}")

    @BeforeEach
    fun start() {
        val key = ClientKeys.generate("demo-client")
        clientKey = ECKey.parse(key.privateJwk)
        keyFile = Files.writeString(directory.resolve("demo-client.jwk.json"), key.privateJwk)
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange -> exchange.use(::handle) }
        server.start()
    }

    @AfterEach
    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        val headers = exchange.requestHeaders.entries.associate { (name, values) -> name.lowercase() to values.first() }
        val body = exchange.requestBody.readAllBytes().decodeToString()
        received += Received(exchange.requestMethod, exchange.requestURI.path, headers, body)
        val (status, answer) = if (exchange.requestURI.path.endsWith(DpopCalls.TOKEN_ENDPOINT_PATH)) tokenStatus to tokenBody else 201 to """{"shortCode":"abc"}"""
        val bytes = answer.toByteArray()
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun form(request: Received): Map<String, String> =
        request.body.split("&").associate { URLDecoder.decode(it.substringBefore("="), StandardCharsets.UTF_8) to URLDecoder.decode(it.substringAfter("="), StandardCharsets.UTF_8) }

    private fun verified(proof: String): SignedJWT =
        SignedJWT.parse(proof).also { assertThat(it.verify(ECDSAVerifier(it.header.jwk as ECKey))).describedAs("signed by the key in its header").isTrue() }

    @Test
    fun `the client signs in with an assertion signed by its key, for the issuer, and asks for a token bound to a proof`() {
        val token = calls.token(keyFile, "demo-client")

        assertThat(token).isEqualTo("the-access-token")
        val request = received.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.headers["content-type"]).isEqualTo("application/x-www-form-urlencoded")
        val form = form(request)
        assertThat(form).containsEntry("grant_type", "client_credentials").containsEntry("client_id", "demo-client")
            .containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer")
        val assertion = SignedJWT.parse(form.getValue("client_assertion"))
        assertThat(assertion.verify(ECDSAVerifier(clientKey.toPublicJWK()))).describedAs("signed by the client's key").isTrue()
        assertThat(assertion.header.algorithm.name).isEqualTo("ES256")
        assertThat(assertion.jwtClaimsSet.issuer).isEqualTo("demo-client")
        assertThat(assertion.jwtClaimsSet.subject).isEqualTo("demo-client")
        assertThat(assertion.jwtClaimsSet.audience).containsExactly("$base/realms/shortener")
        assertThat(assertion.jwtClaimsSet.jwtid).isNotBlank()
        assertThat(assertion.jwtClaimsSet.expirationTime.time - assertion.jwtClaimsSet.issueTime.time).isEqualTo(60_000)

        val proof = verified(request.headers.getValue("dpop"))
        assertThat(proof.header.type.toString()).isEqualTo("dpop+jwt")
        assertThat(proof.jwtClaimsSet.getStringClaim("htm")).isEqualTo("POST")
        assertThat(proof.jwtClaimsSet.getStringClaim("htu")).isEqualTo("$base/realms/shortener${DpopCalls.TOKEN_ENDPOINT_PATH}")
        assertThat(proof.jwtClaimsSet.getClaim("ath")).describedAs("no token yet to hash").isNull()
        assertThat((proof.header.jwk as ECKey).isPrivate).describedAs("the header has no private part").isFalse()
        assertThat(proof.header.jwk.toJSONString()).describedAs("not the client's key").isNotEqualTo(clientKey.toPublicJWK().toJSONString())
    }

    @Test
    fun `the call carries the token under the DPoP scheme and a proof of that request that hashes the token`() {
        val answer = calls.call(keyFile, "demo-client", "POST", "$base/api/short-links?ignored=yes#fragment", """{"targetUrl":"https://example.com"}""")

        assertThat(answer.status).isEqualTo(201)
        assertThat(answer.body).isEqualTo("""{"shortCode":"abc"}""")
        val (tokenRequest, apiRequest) = received
        assertThat(apiRequest.headers["authorization"]).isEqualTo("DPoP the-access-token")
        assertThat(apiRequest.headers["content-type"]).isEqualTo("application/json")
        assertThat(apiRequest.body).isEqualTo("""{"targetUrl":"https://example.com"}""")
        val proof = verified(apiRequest.headers.getValue("dpop"))
        assertThat(proof.jwtClaimsSet.getStringClaim("htm")).isEqualTo("POST")
        assertThat(proof.jwtClaimsSet.getStringClaim("htu")).describedAs("without query and fragment").isEqualTo("$base/api/short-links")
        val hash = Base64URL.encode(MessageDigest.getInstance("SHA-256").digest("the-access-token".toByteArray(StandardCharsets.US_ASCII)))
        assertThat(proof.jwtClaimsSet.getStringClaim("ath")).isEqualTo(hash.toString())
        assertThat(proof.jwtClaimsSet.jwtid).isNotBlank()
        val tokenProof = verified(tokenRequest.headers.getValue("dpop"))
        assertThat(proof.header.jwk.toJSONString()).describedAs("the token was asked for with this key").isEqualTo(tokenProof.header.jwk.toJSONString())
    }

    @Test
    fun `a call without a body sends no content type, and every call signs in again with a key of its own`() {
        calls.call(keyFile, "demo-client", "GET", "$base/api/short-links/abc", null)
        calls.call(keyFile, "demo-client", "GET", "$base/api/short-links/abc", "")

        val (_, first, _, second) = received
        assertThat(first.headers).doesNotContainKey("content-type")
        assertThat(second.headers).doesNotContainKey("content-type")
        assertThat(received.filter { it.path.endsWith(DpopCalls.TOKEN_ENDPOINT_PATH) }).hasSize(2)
        assertThat(verified(first.headers.getValue("dpop")).header.jwk.toJSONString())
            .isNotEqualTo(verified(second.headers.getValue("dpop")).header.jwk.toJSONString())
    }

    @Test
    fun `an identity provider that says no is a failure with its words, and an answer that is not a token is one too`() {
        tokenStatus = 401
        tokenBody = """{"error":"invalid_client"}"""
        assertThatThrownBy { calls.token(keyFile, "demo-client") }.isInstanceOf(Failure::class.java)
            .hasMessage("""the token endpoint answered 401: {"error":"invalid_client"}""")

        tokenStatus = 200
        tokenBody = "<html>"
        assertThatThrownBy { calls.token(keyFile, "demo-client") }.isInstanceOf(Failure::class.java).hasMessageContaining("the token endpoint did not answer JSON")

        tokenBody = """{"token_type":"DPoP"}"""
        assertThatThrownBy { calls.token(keyFile, "demo-client") }.isInstanceOf(Failure::class.java).hasMessageContaining("did not return an access_token")
    }

    @Test
    fun `a server that is not there is a failure that says where`() {
        val closed = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        val gone = DpopCalls("http://localhost:$closed${DpopCalls.TOKEN_ENDPOINT_PATH}")

        assertThatThrownBy { gone.token(keyFile, "demo-client") }.isInstanceOf(Failure::class.java).hasMessageContaining("could not reach http://localhost:$closed")
    }

    @Test
    fun `the proof of a URL with a path keeps the host and the port and drops the rest`() {
        val key = ClientKeys.newKeyPair()

        val proof = SignedJWT.parse(DpopCalls.proof(key, "GET", "https://api.example.com:8443/a/b?x=1#y", "t"))

        assertThat(proof.jwtClaimsSet.getStringClaim("htu")).isEqualTo("https://api.example.com:8443/a/b")
        assertThat(proof.jwtClaimsSet.getStringClaim("htm")).isEqualTo("GET")
    }
}
