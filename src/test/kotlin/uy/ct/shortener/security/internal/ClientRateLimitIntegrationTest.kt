package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class ClientRateLimitIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private fun create(client: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(TestIdp.token(client = client))
        }
        return restTemplate.exchange(
            "/api/short-links",
            HttpMethod.POST,
            HttpEntity("""{"targetUrl":"https://example.com/limited"}""", headers),
            String::class.java,
        )
    }

    @Test
    fun `limits link creation per client`() {
        repeat(3) { assertThat(create("noisy-client").statusCode).isEqualTo(HttpStatus.CREATED) }

        val limited = create("noisy-client")

        assertThat(limited.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(limited.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(limited.headers.getFirst(HttpHeaders.RETRY_AFTER)!!.toLong()).isBetween(1, 60)
        assertThat(create("quiet-client").statusCode).isEqualTo(HttpStatus.CREATED)
    }
}
