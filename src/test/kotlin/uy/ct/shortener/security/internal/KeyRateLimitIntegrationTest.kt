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
import uy.ct.shortener.TestApiKeys
import uy.ct.shortener.TestcontainersConfiguration

/**
 * Link creation is limited per API key: once a key's bucket is empty it is answered with a 429
 * and `Retry-After`, while another key is unaffected.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        TestApiKeys.PROPERTY,
        "shortener.security.api-keys.other-client=c6eb118afa9e53f88ef58718ee07f0a3d698fe2d558580421e4d9bc3c5024d44",
        "shortener.security.rate-limit.per-key.capacity=3",
        "shortener.security.rate-limit.per-client.capacity=1000",
    ],
)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class KeyRateLimitIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    private fun create(key: String): ResponseEntity<String> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(key)
        }
        return restTemplate.exchange(
            "/api/short-links",
            HttpMethod.POST,
            HttpEntity("""{"targetUrl":"https://example.com/limited"}""", headers),
            String::class.java,
        )
    }

    @Test
    fun `limits link creation per api key`() {
        repeat(3) { assertThat(create(TestApiKeys.PLAINTEXT).statusCode).isEqualTo(HttpStatus.CREATED) }

        val limited = create(TestApiKeys.PLAINTEXT)

        assertThat(limited.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
        assertThat(limited.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(limited.headers.getFirst(HttpHeaders.RETRY_AFTER)!!.toLong()).isBetween(1, 60)
        assertThat(create("shk_test_other_client_key_9a8b7c6d5e4f").statusCode).isEqualTo(HttpStatus.CREATED)
    }
}
