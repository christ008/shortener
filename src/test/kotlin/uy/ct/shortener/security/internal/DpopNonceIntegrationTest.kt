package uy.ct.shortener.security.internal

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.TestDpopClient
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp

/**
 * With nonces on, a proof must carry the one the server last handed out. The first call of a client has none, so it is answered
 * `401` with `error="use_dpop_nonce"` and the nonce in `DPoP-Nonce`, and the same call with that nonce is served. A nonce the
 * server did not make is asked for again, and the nonce check does not stand in for the rest of the proof.
 */
@WithTestIdp
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["shortener.security.dpop.required=true", "shortener.security.dpop.nonce.enabled=true", "shortener.security.dpop.nonce.secret=test-secret"],
)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class DpopNonceIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    @Autowired
    lateinit var meters: MeterRegistry

    @LocalServerPort
    var port: Int = 0

    private val client = TestDpopClient()

    private val url get() = "http://localhost:$port/api/short-links"

    private fun list(token: String = client.token(), nonce: String? = null, signWith: TestDpopClient = client): RestTestClient.ResponseSpec {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set(HttpHeaders.AUTHORIZATION, "DPoP $token")
            set("DPoP", signWith.proof("GET", url, token, nonce = nonce))
        }
        return rest.request(HttpMethod.GET, url, headers)
    }

    private fun nonceOf(response: RestTestClient.ResponseSpec): String = response.returnResult().responseHeaders.getFirst("DPoP-Nonce").orEmpty()

    private fun RestTestClient.ResponseSpec.unauthorizedWith(check: (String) -> Unit) = expectStatus().isUnauthorized()
        .expectHeader().values(HttpHeaders.WWW_AUTHENTICATE) { challenges -> assertThat(challenges).singleElement().satisfies({ check(it) }) }

    private fun events(type: String) = meters.counter("shortener.security.events", "type", type).count()

    @Test
    fun `a proof without a nonce is answered with the nonce to use, and the call with it is served`() {
        val token = client.token()

        val asked = list(token)
        asked.unauthorizedWith { assertThat(it).startsWith("DPoP realm=\"shortener\"").contains("error=\"use_dpop_nonce\"") }
        assertThat(nonceOf(asked)).isNotBlank()

        list(token, nonce = nonceOf(asked)).expectStatus().isOk()
    }

    @Test
    fun `a served response hands out the nonce too, so the client keeps it current`() {
        val token = client.token()
        val nonce = nonceOf(list(token))

        val served = list(token, nonce = nonce)

        served.expectStatus().isOk()
        assertThat(nonceOf(served)).isEqualTo(nonce)
    }

    @Test
    fun `a nonce the server did not make is asked for again`() {
        val token = client.token()
        val foreign = HmacDpopNonces("another-secret".toByteArray(), java.time.Duration.ofMinutes(5)).current()

        val response = list(token, nonce = foreign)

        response.unauthorizedWith { assertThat(it).contains("error=\"use_dpop_nonce\"") }
        assertThat(nonceOf(response)).isNotBlank().isNotEqualTo(foreign)
    }

    @Test
    fun `the right nonce does not make a proof signed by another key valid`() {
        val token = client.token()
        val nonce = nonceOf(list(token))

        list(token, nonce = nonce, signWith = TestDpopClient()).unauthorizedWith { assertThat(it).contains("error=\"invalid_dpop_proof\"") }
    }

    @Test
    fun `asking for a nonce is counted apart, and is not a failed authentication`() {
        val asked = events("dpop_nonce_requested")
        val failed = events("unauthenticated")

        list()

        assertThat(events("dpop_nonce_requested")).isEqualTo(asked + 1)
        assertThat(events("unauthenticated")).isEqualTo(failed)
    }

    @Test
    fun `a request that is not under the DPoP scheme is handed no nonce`() {
        rest.request(HttpMethod.GET, url)
            .expectStatus().isUnauthorized()
            .expectHeader().doesNotExist("DPoP-Nonce")
    }
}
