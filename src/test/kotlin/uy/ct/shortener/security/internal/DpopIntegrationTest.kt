package uy.ct.shortener.security.internal

import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class DpopIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @LocalServerPort
    var port: Int = 0

    private val client = TestDpopClient()

    private val url get() = "http://localhost:$port/api/short-links"

    private fun call(
        method: HttpMethod = HttpMethod.GET,
        authorization: String? = null,
        proof: String? = null,
    ): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            authorization?.let { set(HttpHeaders.AUTHORIZATION, it) }
            proof?.let { set("DPoP", it) }
        }
        val body = if (method == HttpMethod.POST) """{"targetUrl":"https://example.com/dpop"}""" else null
        return restTemplate.exchange(url, method, HttpEntity(body, headers), String::class.java)
    }

    private fun bound(method: HttpMethod = HttpMethod.GET, token: String = client.token(), proof: (String) -> String): ResponseEntity<String> =
        call(method, "DPoP $token", proof(token))

    private fun assertRejected(response: ResponseEntity<String>, error: String) {
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers[HttpHeaders.WWW_AUTHENTICATE]).singleElement().satisfies({
            assertThat(it).startsWith("DPoP realm=\"shortener\"").contains("error=\"$error\"")
        })
    }

    @Test
    fun `serves a request made with a bound token and a proof made for it`() {
        val created = bound(HttpMethod.POST) { client.proof("POST", url, it) }
        val listed = bound { client.proof("GET", url, it) }

        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(listed.statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `challenges with the DPoP scheme and its algorithms alone when no credentials are sent`() {
        val response = call()

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers[HttpHeaders.WWW_AUTHENTICATE]).singleElement().satisfies({
            assertThat(it).startsWith("DPoP realm=\"shortener\"").contains("algs=\"").contains("ES256")
        })
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

        val first = call(authorization = "DPoP $token", proof = client.proof("GET", url, token, jti = jti))
        val replay = call(authorization = "DPoP $token", proof = client.proof("GET", url, token, jti = jti))

        assertThat(first.statusCode).isEqualTo(HttpStatus.OK)
        assertRejected(replay, "invalid_dpop_proof")
    }

    @Test
    fun `refuses an access token that is not of type at+jwt`() {
        val token = TestIdp.token(type = "JWT", jkt = client.thumbprint)

        assertRejected(call(authorization = "DPoP $token", proof = client.proof("GET", url, token)), "invalid_token")
    }

    @Test
    fun `answers a bound token without the needed scope with a 403 that names the DPoP scheme`() {
        val token = client.token(scope = "shortlinks:read")

        val response = call(HttpMethod.POST, "DPoP $token", client.proof("POST", url, token))

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.headers[HttpHeaders.WWW_AUTHENTICATE]).singleElement().satisfies({
            assertThat(it).startsWith("DPoP realm=\"shortener\"").contains("error=\"insufficient_scope\"")
        })
    }

    @Test
    fun `publishes what clients need to know about it as protected resource metadata`() {
        val metadata = restTemplate.getForEntity("http://localhost:$port/.well-known/oauth-protected-resource", String::class.java)

        assertThat(metadata.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(metadata.body)
            .contains(""""authorization_servers":["${TestIdp.ISSUER}"]""")
            .contains(""""dpop_bound_access_tokens_required":true""")
            .contains(""""dpop_signing_alg_values_supported":["RS256"""")
            .contains(""""tls_client_certificate_bound_access_tokens":false""")
    }

    @Test
    fun `leaves following a short link public`() {
        val response = restTemplate.getForEntity("http://localhost:$port/unknown1", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
