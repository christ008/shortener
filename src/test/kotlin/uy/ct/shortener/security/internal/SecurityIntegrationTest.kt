package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
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
 * End-to-end tests of the security filter chain: which requests need an API key, how failures
 * are reported, and which headers every response carries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [TestApiKeys.PROPERTY])
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class SecurityIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @LocalManagementPort
    var managementPort: Int = 0

    private fun headers(authorization: String? = null) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        authorization?.let { set(HttpHeaders.AUTHORIZATION, it) }
    }

    private fun create(authorization: String?): ResponseEntity<String> =
        restTemplate.exchange(
            "/api/short-links",
            HttpMethod.POST,
            HttpEntity("""{"targetUrl":"https://example.com/secured"}""", headers(authorization)),
            String::class.java,
        )

    private fun get(path: String, authorization: String? = null): ResponseEntity<String> =
        restTemplate.exchange(path, HttpMethod.GET, HttpEntity<Void>(headers(authorization)), String::class.java)

    @Test
    fun `rejects link creation without an api key`() {
        val response = create(authorization = null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("""Bearer realm="shortener"""")
        assertThat(response.body).contains(""""status":401""")
    }

    @Test
    fun `rejects an unknown api key and says the token was invalid`() {
        val response = create("Bearer shk_not_a_real_key")

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("""error="invalid_token"""")
    }

    @Test
    fun `ignores credentials sent under other schemes`() {
        assertThat(create("Basic dGVzdC1jbGllbnQ6c2VjcmV0").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(create(TestApiKeys.PLAINTEXT).statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `accepts a valid api key, whatever the case of the scheme`() {
        assertThat(create("Bearer ${TestApiKeys.PLAINTEXT}").statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(create("bearer ${TestApiKeys.PLAINTEXT}").statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `following a short link needs no api key`() {
        val code = Regex(""""shortCode":"([^"]+)"""").find(create("Bearer ${TestApiKeys.PLAINTEXT}").body!!)!!.groupValues[1]

        val redirect = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).getForEntity("/$code", Void::class.java)

        assertThat(redirect.statusCode).isEqualTo(HttpStatus.FOUND)
    }

    @Test
    fun `health probes are public on the management port but other actuator endpoints are not`() {
        assertThat(get("http://localhost:$managementPort/actuator/health").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(get("http://localhost:$managementPort/actuator/health/liveness").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(get("http://localhost:$managementPort/actuator/env").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `denies every unlisted path, and answers an authenticated client with a 403 problem detail`() {
        assertThat(get("/api/unknown/thing").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val response = get("/api/unknown/thing", "Bearer ${TestApiKeys.PLAINTEXT}")

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `every response carries restrictive security headers`() {
        val headers = get("/zzzzzzz").headers

        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff")
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY")
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer")
        assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'")
    }
}
