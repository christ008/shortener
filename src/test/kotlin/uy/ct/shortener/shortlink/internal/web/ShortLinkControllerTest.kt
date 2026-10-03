package uy.ct.shortener.shortlink.internal.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import uy.ct.shortener.TestIdp
import uy.ct.shortener.WithTestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkRepository

/**
 * End-to-end tests of the HTTP API against a running server and real Postgres. Redirect
 * following is disabled so the 302 itself can be asserted.
 */
@WithTestIdp
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class ShortLinkControllerTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var repository: ShortLinkRepository

    private fun <T : Any> create(body: CreateShortLinkRequest, type: Class<T>): ResponseEntity<T> {
        val headers = HttpHeaders().apply { setBearerAuth(TestIdp.token()) }
        return restTemplate.exchange("/api/short-links", HttpMethod.POST, HttpEntity(body, headers), type)
    }

    @Test
    fun `creates a short link and redirects through it`() {
        val created = create(CreateShortLinkRequest("https://example.com/some/long/path"), ShortLinkResponse::class.java)

        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val shortCode = created.body!!.shortCode
        assertThat(shortCode).hasSize(ShortCode.GENERATED_LENGTH)
        assertThat(created.headers.location.toString()).endsWith("/$shortCode")
        assertThat(repository.findByShortCode(ShortCode(shortCode))?.createdBy).isEqualTo(TestIdp.CLIENT)

        val redirect = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).getForEntity("/$shortCode", Void::class.java)

        assertThat(redirect.statusCode).isEqualTo(HttpStatus.FOUND)
        assertThat(redirect.headers.location.toString()).isEqualTo("https://example.com/some/long/path")
    }

    @Test
    fun `answers a body without a target url, or with a null one, with a 400 rather than a 500`() {
        val headers = HttpHeaders().apply {
            setBearerAuth(TestIdp.token())
            contentType = MediaType.APPLICATION_JSON
        }

        listOf("{}", """{"targetUrl":null}""", """{"customCode":"my-promo"}""").forEach { body ->
            val response = restTemplate.exchange("/api/short-links", HttpMethod.POST, HttpEntity(body, headers), ProblemDetail::class.java)

            assertThat(response.statusCode).describedAs(body).isEqualTo(HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `rejects a blank target url with a 400 problem detail`() {
        val response = create(CreateShortLinkRequest(""), ProblemDetail::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `404s on a well-formed but unknown short code`() {
        val response = restTemplate.getForEntity("/zzzzzzz", ProblemDetail::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body?.detail).contains("zzzzzzz")
    }

    @Test
    fun `rejects a malformed short code before it reaches the service`() {
        val response = restTemplate.getForEntity("/ab", ProblemDetail::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `creates a short link under a custom code and redirects through it`() {
        val created = create(CreateShortLinkRequest("https://example.com/promo", "promo-2026"), ShortLinkResponse::class.java)

        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(created.body!!.shortCode).isEqualTo("promo-2026")
        assertThat(created.headers.location.toString()).endsWith("/promo-2026")

        val redirect = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).getForEntity("/promo-2026", Void::class.java)

        assertThat(redirect.statusCode).isEqualTo(HttpStatus.FOUND)
        assertThat(redirect.headers.location.toString()).isEqualTo("https://example.com/promo")
    }

    @Test
    fun `409s when the custom code is already taken`() {
        val request = CreateShortLinkRequest("https://example.com/first", "taken-code")
        create(request, ShortLinkResponse::class.java)

        val response = create(request, ProblemDetail::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body?.detail).contains("taken-code")
    }

    @Test
    fun `409s when the custom code is reserved`() {
        val response = create(CreateShortLinkRequest("https://example.com", "actuator"), ProblemDetail::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `rejects a malformed custom code with a 400`() {
        val response = create(CreateShortLinkRequest("https://example.com", "no spaces!"), ProblemDetail::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
