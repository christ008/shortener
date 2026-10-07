package uy.ct.shortener.shortlink.internal.web

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
import org.springframework.http.ProblemDetail
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.RestTestClientSupport
import uy.ct.shortener.RestTestClientSupport.expectStatus
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.internal.found

/**
 * End-to-end tests of the HTTP API against a running server and real Postgres. Redirect
 * following is disabled so the 302 itself can be asserted.
 */
@WithTestIdp
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class ShortLinkControllerTest {

    @Autowired
    lateinit var rest: RestTestClient

    @LocalServerPort
    var port: Int = 0

    private val noRedirects by lazy { RestTestClientSupport.withoutRedirects(port) }

    @Autowired
    lateinit var repository: ShortLinkRepository

    private fun bearer() = HttpHeaders().apply { setBearerAuth(TestIdp.token()) }

    private fun create(body: CreateShortLinkRequest) = rest.request(HttpMethod.POST, "/api/short-links", bearer(), body)

    private fun claim(code: String, target: String) = rest.request(HttpMethod.PUT, "/api/short-links/$code", bearer(), ClaimShortLinkRequest(target))

    @Test
    fun `creates a short link and redirects through it`() {
        val created = create(CreateShortLinkRequest("https://example.com/some/long/path")).expectStatus().isCreated().returnResult(ShortLinkResponse::class.java)

        val shortCode = created.responseBody!!.shortCode
        assertThat(shortCode).hasSize(ShortCode.GENERATED_LENGTH)
        assertThat(created.responseHeaders.location.toString()).endsWith("/api/short-links/$shortCode")
        assertThat(created.responseBody!!.shortUrl).endsWith("/$shortCode").doesNotContain("/api/")
        assertThat(repository.findByShortCode(ShortCode(shortCode)).found().createdBy).isEqualTo(Actor.Client(TestIdp.CLIENT))

        noRedirects.get().uri("/$shortCode").exchange()
            .expectStatus().isFound()
            .expectHeader().location("https://example.com/some/long/path")
    }

    @Test
    fun `the Location of a created link is the link, and following it reads what was created`() {
        val created = create(CreateShortLinkRequest("https://example.com/located")).expectStatus().isCreated().returnResult(ShortLinkResponse::class.java)

        rest.get().uri(created.responseHeaders.location!!).headers { it.addAll(bearer()) }.exchange()
            .expectStatus().isOk()
            .expectBody(ShortLinkResponse::class.java).isEqualTo(created.responseBody!!)
    }

    @Test
    fun `answers a body without a target url, or with a null one, with a 400 rather than a 500`() {
        val headers = bearer().apply { contentType = MediaType.APPLICATION_JSON }

        listOf("{}", """{"targetUrl":null}""", """{"customCode":"my-promo"}""").forEach { body ->
            rest.request(HttpMethod.POST, "/api/short-links", headers, body)
                .expectStatus(body, HttpStatus.BAD_REQUEST)
        }
    }

    @Test
    fun `rejects a blank target url with a 400 problem detail`() {
        create(CreateShortLinkRequest("")).expectStatus().isBadRequest()
    }

    @Test
    fun `404s on a well-formed but unknown short code`() {
        rest.get().uri("/zzzzzzz").exchange()
            .expectStatus().isNotFound()
            .expectBody(ProblemDetail::class.java).value { assertThat(it?.detail).contains("zzzzzzz") }
    }

    @Test
    fun `rejects a malformed short code before it reaches the service`() {
        rest.get().uri("/ab").exchange().expectStatus().isBadRequest()
    }

    @Test
    fun `creates a short link under a custom code and redirects through it`() {
        val created = claim("promo-2026", "https://example.com/promo").expectStatus().isCreated().returnResult(ShortLinkResponse::class.java)

        assertThat(created.responseBody!!.shortCode).isEqualTo("promo-2026")
        assertThat(created.responseHeaders.location.toString()).endsWith("/api/short-links/promo-2026")
        assertThat(created.responseBody!!.shortUrl).endsWith("/promo-2026").doesNotContain("/api/")

        noRedirects.get().uri("/promo-2026").exchange()
            .expectStatus().isFound()
            .expectHeader().location("https://example.com/promo")
    }

    @Test
    fun `409s when the custom code is already taken for another target, and 200s when the claim is repeated`() {
        val first = claim("taken-code", "https://example.com/first").expectStatus().isCreated().returnResult(ShortLinkResponse::class.java)

        claim("taken-code", "https://example.com/first")
            .expectStatus().isOk()
            .expectBody(ShortLinkResponse::class.java).isEqualTo(first.responseBody!!)
        claim("taken-code", "https://example.com/second")
            .expectStatus().isEqualTo(HttpStatus.CONFLICT)
            .expectBody(ProblemDetail::class.java).value { assertThat(it?.detail).contains("taken-code") }
    }

    @Test
    fun `409s when the custom code is reserved`() {
        claim("actuator", "https://example.com").expectStatus().isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `rejects a malformed custom code with a 400`() {
        claim("bad.code", "https://example.com").expectStatus().isBadRequest()
    }
}
