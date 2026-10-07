package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp

/**
 * Link creation is limited per authenticated client: once a client's bucket is empty it is
 * answered with a 429 and `Retry-After`, while another client is unaffected.
 */
@WithTestIdp
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "shortener.security.rate-limit.per-client.capacity=3",
        "shortener.security.rate-limit.per-ip.capacity=1000",
    ],
)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class ClientRateLimitIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    private fun create(client: String): RestTestClient.ResponseSpec {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(TestIdp.token(client = client))
        }
        return rest.request(HttpMethod.POST, "/api/short-links", headers, """{"targetUrl":"https://example.com/limited"}""")
    }

    @Test
    fun `limits link creation per client`() {
        repeat(3) { create("noisy-client").expectStatus().isCreated() }

        create("noisy-client")
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader().value(HttpHeaders.RETRY_AFTER) { assertThat(it.toLong()).isBetween(1, 60) }
        create("quiet-client").expectStatus().isCreated()
    }
}
