package uy.ct.shortener.shortlink.internal.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.shortlink.ShortCode

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class ShortLinkControllerTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Test
    fun `creates a short link and redirects through it`() {
        val created = restTemplate.postForEntity(
            "/api/short-links",
            CreateShortLinkRequest("https://example.com/some/long/path"),
            ShortLinkResponse::class.java,
        )

        assertThat(created.statusCode).isEqualTo(HttpStatus.CREATED)
        val shortCode = created.body!!.shortCode
        assertThat(shortCode).hasSize(ShortCode.LENGTH)
        assertThat(created.headers.location.toString()).endsWith("/$shortCode")

        val redirect = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).getForEntity("/$shortCode", Void::class.java)

        assertThat(redirect.statusCode).isEqualTo(HttpStatus.FOUND)
        assertThat(redirect.headers.location.toString()).isEqualTo("https://example.com/some/long/path")
    }

    @Test
    fun `rejects a blank target url with a 400 problem detail`() {
        val response = restTemplate.postForEntity(
            "/api/short-links",
            CreateShortLinkRequest(""),
            ProblemDetail::class.java,
        )

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
        val response = restTemplate.getForEntity("/too-long-to-be-a-code", ProblemDetail::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    }
}
