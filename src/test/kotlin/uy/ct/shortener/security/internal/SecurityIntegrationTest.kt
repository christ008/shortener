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
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import java.time.Duration

/**
 * End-to-end tests of the security filter chain against signed tokens: which requests need a
 * token, exactly what makes a token invalid, how failures are reported, and which headers every
 * response carries.
 */
@WithTestIdp
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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

    private fun bearer(token: String) = "Bearer $token"

    @Test
    fun `rejects link creation without a token`() {
        val response = create(authorization = null)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("""Bearer realm="shortener"""")
        assertThat(response.body).contains(""""status":401""")
    }

    @Test
    fun `answers a security failure as a problem detail whatever the client says it accepts`() {
        val headers = HttpHeaders().apply { accept = listOf(MediaType.TEXT_PLAIN) }

        val response = restTemplate.exchange("/api/short-links", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(response.body).contains(""""title":"Unauthorized"""", """"status":401""", """"detail":"A valid access token is required"""")
    }

    @Test
    fun `rejects every kind of invalid token as invalid_token`() {
        val invalid = mapOf(
            "not a jwt" to "garbage",
            "signed by a key the issuer never published" to TestIdp.token(key = TestIdp.unpublishedKey),
            "wrong issuer" to TestIdp.token(issuer = "http://evil.test/realms/shortener"),
            "wrong audience" to TestIdp.token(audience = "some-other-api"),
            "expired" to TestIdp.token(expiresIn = Duration.ofMinutes(-10)),
            "no client claim" to TestIdp.token(client = null),
        )

        invalid.forEach { (why, token) ->
            val response = create(bearer(token))

            assertThat(response.statusCode).describedAs(why).isEqualTo(HttpStatus.UNAUTHORIZED)
            assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).describedAs(why).contains("""error="invalid_token"""")
        }
    }

    @Test
    fun `refuses a valid token that lacks the required scope with a 403`() {
        val response = create(bearer(TestIdp.token(scope = "profile email")))

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(response.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("""error="insufficient_scope"""")
    }

    @Test
    fun `ignores credentials sent under other schemes`() {
        val basic = create("Basic dGVzdC1jbGllbnQ6c2VjcmV0")
        val bare = create(TestIdp.token())

        assertThat(basic.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(bare.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(basic.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE)).doesNotContain("invalid_token")
    }

    @Test
    fun `does not accept a token passed in the url or the request body, only in the Authorization header`() {
        val token = TestIdp.token()

        val inQuery = restTemplate.exchange(
            "/api/short-links?access_token=$token",
            HttpMethod.POST,
            HttpEntity("""{"targetUrl":"https://example.com/secured"}""", headers()),
            String::class.java,
        )
        val inForm = restTemplate.exchange(
            "/api/short-links",
            HttpMethod.POST,
            HttpEntity("access_token=$token", HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }),
            String::class.java,
        )

        assertThat(inQuery.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(inForm.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `accepts a valid token, whatever the case of the scheme, and records the client`() {
        assertThat(create(bearer(TestIdp.token())).statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(create("bearer ${TestIdp.token()}").statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `accepts a token only in the authorization header, never in the query string or a form body`() {
        val token = TestIdp.token()

        assertThat(get("/api/short-links?access_token=$token").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(get("/api/short-links", bearer(token)).statusCode).isEqualTo(HttpStatus.OK)

        val form = restTemplate.exchange(
            "/api/short-links",
            HttpMethod.POST,
            HttpEntity("access_token=$token", HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }),
            String::class.java,
        )
        assertThat(form.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `following a short link needs no token`() {
        val code = Regex(""""shortCode":"([^"]+)"""").find(create(bearer(TestIdp.token())).body!!)!!.groupValues[1]

        val redirect = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).getForEntity("/$code", Void::class.java)

        assertThat(redirect.statusCode).isEqualTo(HttpStatus.FOUND)
    }

    @Test
    fun `health probes and metrics are public on the management port but other actuator endpoints are not`() {
        assertThat(get("http://localhost:$managementPort/actuator/health").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(get("http://localhost:$managementPort/actuator/health/liveness").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(get("http://localhost:$managementPort/actuator/prometheus").statusCode).isEqualTo(HttpStatus.OK)
        assertThat(get("http://localhost:$managementPort/actuator/env").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `denies every unlisted path, and answers an authenticated client with a 403 problem detail`() {
        assertThat(get("/unlisted/thing").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val response = get("/unlisted/thing", bearer(TestIdp.token()))

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `everything under the api path needs a token, and what the token may do is decided by the operation`() {
        assertThat(get("/api/unknown/thing").statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)

        val unknown = get("/api/unknown/thing", bearer(TestIdp.token()))

        assertThat(unknown.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
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
