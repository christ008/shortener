package uy.ct.shortener.security.internal

import io.micrometer.core.instrument.MeterRegistry
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
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class DpopNonceIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var meters: MeterRegistry

    @LocalServerPort
    var port: Int = 0

    private val client = TestDpopClient()

    private val url get() = "http://localhost:$port/api/short-links"

    private fun list(token: String = client.token(), nonce: String? = null, signWith: TestDpopClient = client): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set(HttpHeaders.AUTHORIZATION, "DPoP $token")
            set("DPoP", signWith.proof("GET", url, token, nonce = nonce))
        }
        return restTemplate.exchange(url, HttpMethod.GET, HttpEntity<String>(headers), String::class.java)
    }

    private fun nonceOf(response: ResponseEntity<String>): String = response.headers.getFirst("DPoP-Nonce").orEmpty()

    private fun events(type: String) = meters.counter("shortener.security.events", "type", type).count()

    @Test
    fun `a proof without a nonce is answered with the nonce to use, and the call with it is served`() {
        val token = client.token()

        val asked = list(token)
        assertThat(asked.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(asked.headers[HttpHeaders.WWW_AUTHENTICATE]).singleElement().satisfies({
            assertThat(it).startsWith("DPoP realm=\"shortener\"").contains("error=\"use_dpop_nonce\"")
        })
        assertThat(nonceOf(asked)).isNotBlank()

        assertThat(list(token, nonce = nonceOf(asked)).statusCode).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `a served response hands out the nonce too, so the client keeps it current`() {
        val token = client.token()
        val nonce = nonceOf(list(token))

        val served = list(token, nonce = nonce)

        assertThat(served.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(nonceOf(served)).isEqualTo(nonce)
    }

    @Test
    fun `a nonce the server did not make is asked for again`() {
        val token = client.token()
        val foreign = HmacDpopNonces("another-secret".toByteArray(), java.time.Duration.ofMinutes(5)).current()

        val response = list(token, nonce = foreign)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers[HttpHeaders.WWW_AUTHENTICATE]).singleElement().satisfies({ assertThat(it).contains("error=\"use_dpop_nonce\"") })
        assertThat(nonceOf(response)).isNotBlank().isNotEqualTo(foreign)
    }

    @Test
    fun `the right nonce does not make a proof signed by another key valid`() {
        val token = client.token()
        val nonce = nonceOf(list(token))

        val response = list(token, nonce = nonce, signWith = TestDpopClient())

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers[HttpHeaders.WWW_AUTHENTICATE]).singleElement().satisfies({ assertThat(it).contains("error=\"invalid_dpop_proof\"") })
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
        val response = restTemplate.exchange(url, HttpMethod.GET, HttpEntity<String>(HttpHeaders()), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.containsHeader("DPoP-Nonce")).isFalse()
    }
}
