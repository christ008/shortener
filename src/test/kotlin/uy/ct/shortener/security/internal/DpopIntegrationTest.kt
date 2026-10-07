package uy.ct.shortener.security.internal

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.TestDpopClient
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import java.time.Instant
import java.util.UUID

/**
 * With sender-constrained tokens required, which is how the application runs by default, every
 * request needs an access token bound to the client's key and a proof of possession of that key
 * made for this very request (RFC 9449). The tests present each way of getting that wrong and
 * expect a 401 that names the `DPoP` scheme and the failure, and a plain bearer token, even a
 * correct one, is refused.
 */
@WithTestIdp
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["shortener.security.dpop.required=true"],
)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class DpopIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    @LocalServerPort
    var port: Int = 0

    private val client = TestDpopClient()

    private val url get() = "http://localhost:$port/api/short-links"

    private fun call(
        method: HttpMethod = HttpMethod.GET,
        authorization: String? = null,
        proof: String? = null,
        forwarded: Map<String, String> = emptyMap(),
    ): RestTestClient.ResponseSpec {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            forwarded.forEach { (name, value) -> set(name, value) }
            authorization?.let { set(HttpHeaders.AUTHORIZATION, it) }
            proof?.let { set("DPoP", it) }
        }
        val body = if (method == HttpMethod.POST) """{"targetUrl":"https://example.com/dpop"}""" else null
        return rest.request(method, url, headers, body)
    }

    private fun bound(method: HttpMethod = HttpMethod.GET, token: String = client.token(), proof: (String) -> String) =
        call(method, "DPoP $token", proof(token))

    private fun RestTestClient.ResponseSpec.challenge(status: HttpStatus, check: (String) -> Unit) = expectStatus().isEqualTo(status)
        .expectHeader().values(HttpHeaders.WWW_AUTHENTICATE) { challenges -> assertThat(challenges).singleElement().satisfies({ check(it) }) }

    private fun assertRejected(response: RestTestClient.ResponseSpec, error: String) {
        response.challenge(HttpStatus.UNAUTHORIZED) { assertThat(it).startsWith("DPoP realm=\"shortener\"").contains("error=\"$error\"") }
    }

    @Test
    fun `serves a request made with a bound token and a proof made for it`() {
        bound(HttpMethod.POST) { client.proof("POST", url, it) }.expectStatus().isCreated()
        bound { client.proof("GET", url, it) }.expectStatus().isOk()
    }

    @Test
    fun `challenges with the DPoP scheme and its algorithms alone when no credentials are sent`() {
        call().challenge(HttpStatus.UNAUTHORIZED) { assertThat(it).startsWith("DPoP realm=\"shortener\"").contains("algs=\"").contains("ES256") }
    }

    @Test
    fun `refuses the bearer scheme, even for a bound token`() {
        val token = client.token()

        assertRejected(call(authorization = "Bearer $token"), "invalid_token")
        assertRejected(call(authorization = "Bearer ${TestIdp.token()}"), "invalid_token")
    }

    @Test
    fun `refuses a token that is not bound to a key, whatever proof comes with it`() {
        val unbound = TestIdp.token()

        assertRejected(call(authorization = "DPoP $unbound", proof = client.proof("GET", url, unbound)), "invalid_dpop_proof")
    }

    @Test
    fun `refuses a request without a proof`() {
        assertRejected(call(authorization = "DPoP ${client.token()}"), "invalid_request")
    }

    @Test
    fun `refuses a proof made for another method, another url or another token`() {
        assertRejected(bound { client.proof("POST", url, it) }, "invalid_dpop_proof")
        assertRejected(bound { client.proof("GET", "http://localhost:$port/api/other", it) }, "invalid_dpop_proof")
        assertRejected(bound { client.proof("GET", url, TestIdp.token()) }, "invalid_dpop_proof")
        assertRejected(bound { client.proof("GET", url, athOverride = "bm90LXRoZS1oYXNo") }, "invalid_dpop_proof")
        assertRejected(bound { client.proof("GET", url) }, "invalid_dpop_proof")
    }

    @Test
    fun `behind the edge the proof names the port the client used, and the default port when none is forwarded`() {
        fun behindEdge(host: String, forwardedPort: String?, signedFor: String): RestTestClient.ResponseSpec {
            val token = client.token()
            val headers = buildMap {
                put(HttpHeaders.HOST, host)
                put("X-Forwarded-Host", host)
                put("X-Forwarded-Proto", "https")
                forwardedPort?.let { put("X-Forwarded-Port", it) }
            }
            return call(authorization = "DPoP $token", proof = client.proof("GET", signedFor, token), forwarded = headers)
        }

        behindEdge("edge.example:9443", "9443", "https://edge.example:9443/api/short-links").expectStatus().isOk()
        behindEdge("edge.example", "443", "https://edge.example/api/short-links").expectStatus().isOk()
        assertRejected(behindEdge("edge.example:9443", null, "https://edge.example:9443/api/short-links"), "invalid_dpop_proof")
    }

    @Test
    fun `refuses a proof signed by a key other than the one the token is bound to`() {
        val thief = ECKeyGenerator(Curve.P_256).generate()

        assertRejected(bound { client.proof("GET", url, it, signWith = thief) }, "invalid_dpop_proof")
    }

    @Test
    fun `refuses a proof that is stale or has the wrong JOSE type`() {
        assertRejected(bound { client.proof("GET", url, it, issuedAt = Instant.now().minusSeconds(600)) }, "invalid_dpop_proof")
        assertRejected(bound { client.proof("GET", url, it, issuedAt = Instant.now().plusSeconds(600)) }, "invalid_dpop_proof")
        assertRejected(bound { client.proof("GET", url, it, type = "JWT") }, "invalid_dpop_proof")
    }

    @Test
    fun `refuses a proof used a second time`() {
        val token = client.token()
        val jti = UUID.randomUUID().toString()

        call(authorization = "DPoP $token", proof = client.proof("GET", url, token, jti = jti)).expectStatus().isOk()
        assertRejected(call(authorization = "DPoP $token", proof = client.proof("GET", url, token, jti = jti)), "invalid_dpop_proof")
    }

    @Test
    fun `refuses an access token that is not of type at+jwt`() {
        val token = TestIdp.token(type = "JWT", jkt = client.thumbprint)

        assertRejected(call(authorization = "DPoP $token", proof = client.proof("GET", url, token)), "invalid_token")
    }

    @Test
    fun `answers a bound token without the needed scope with a 403 that names the DPoP scheme`() {
        val token = client.token(scope = "shortlinks:read")

        call(HttpMethod.POST, "DPoP $token", client.proof("POST", url, token))
            .challenge(HttpStatus.FORBIDDEN) { assertThat(it).startsWith("DPoP realm=\"shortener\"").contains("error=\"insufficient_scope\"") }
    }

    @Test
    fun `publishes what clients need to know about it as protected resource metadata`() {
        rest.get().uri("http://localhost:$port/.well-known/oauth-protected-resource").exchange()
            .expectStatus().isOk()
            .expectBody(String::class.java).value {
                assertThat(it)
                    .contains(""""authorization_servers":["${TestIdp.ISSUER}"]""")
                    .contains(""""dpop_bound_access_tokens_required":true""")
                    .contains(""""dpop_signing_alg_values_supported":["RS256"""")
                    .contains(""""tls_client_certificate_bound_access_tokens":false""")
            }
    }

    @Test
    fun `leaves following a short link public`() {
        rest.get().uri("http://localhost:$port/unknown1").exchange().expectStatus().isNotFound()
    }
}
