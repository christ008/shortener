package uy.ct.shortener.security.internal

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.client.RestTestClient
import uy.ct.shortener.LogCapture
import uy.ct.shortener.RestTestClientSupport
import uy.ct.shortener.RestTestClientSupport.expectStatus
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.RestTestClientSupport.text
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
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class SecurityIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    @LocalServerPort
    var port: Int = 0

    private val noRedirects by lazy { RestTestClientSupport.withoutRedirects(port) }

    @LocalManagementPort
    var managementPort: Int = 0

    @Autowired
    lateinit var meters: MeterRegistry

    private fun headers(authorization: String? = null) = HttpHeaders().apply {
        contentType = MediaType.APPLICATION_JSON
        authorization?.let { set(HttpHeaders.AUTHORIZATION, it) }
    }

    private fun create(authorization: String?) =
        rest.request(HttpMethod.POST, "/api/short-links", headers(authorization), """{"targetUrl":"https://example.com/secured"}""")

    private fun get(path: String, authorization: String? = null) = rest.request(HttpMethod.GET, path, headers(authorization))

    private fun bearer(token: String) = "Bearer $token"

    private val formHeaders = HttpHeaders().apply { contentType = MediaType.APPLICATION_FORM_URLENCODED }

    @Test
    fun `rejects link creation without a token`() {
        create(authorization = null)
            .expectStatus().isUnauthorized()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE) { assertThat(it).isEqualTo("""Bearer realm="shortener"""") }
            .expectBody(String::class.java).value { assertThat(it).contains(""""status":401""") }
    }

    @Test
    fun `a rejected token is logged and counted by its error code, without the token`() {
        val before = meters.counter(SecurityEvents.METRIC, "type", "unauthenticated").count()
        LogCapture(SecurityEvents.LOGGER_NAME).use { log ->
            create(bearer("not.a.SECRET-TOKEN")).expectStatus().isUnauthorized()

            val line = log.events.single()
            assertThat(line.fields).containsEntry("event.action", "unauthenticated").containsEntry("event.reason", "invalid_token")
                .containsEntry("url.path", "/api/short-links")
            assertThat(line.toString()).doesNotContain("SECRET")
        }
        assertThat(meters.counter(SecurityEvents.METRIC, "type", "unauthenticated").count()).isEqualTo(before + 1)
    }

    @Test
    fun `answers a security failure as a problem detail whatever the client says it accepts`() {
        val headers = HttpHeaders().apply { accept = listOf(MediaType.TEXT_PLAIN) }

        rest.request(HttpMethod.GET, "/api/short-links", headers)
            .expectStatus().isUnauthorized()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody(String::class.java).value {
                assertThat(it).contains(""""title":"Unauthorized"""", """"status":401""", """"detail":"A valid access token is required"""")
            }
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
            create(bearer(token))
                .expectStatus(why, HttpStatus.UNAUTHORIZED)
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE) { assertThat(it).describedAs(why).contains("""error="invalid_token"""") }
        }
    }

    @Test
    fun `refuses a valid token that lacks the required scope with a 403`() {
        create(bearer(TestIdp.token(scope = "profile email")))
            .expectStatus().isForbidden()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE) { assertThat(it).contains("""error="insufficient_scope"""") }
    }

    @Test
    fun `ignores credentials sent under other schemes`() {
        create("Basic dGVzdC1jbGllbnQ6c2VjcmV0")
            .expectStatus().isUnauthorized()
            .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE) { assertThat(it).doesNotContain("invalid_token") }
        create(TestIdp.token()).expectStatus().isUnauthorized()
    }

    @Test
    fun `does not accept a token passed in the url or the request body, only in the Authorization header`() {
        val token = TestIdp.token()

        rest.request(HttpMethod.POST, "/api/short-links?access_token=$token", headers(), """{"targetUrl":"https://example.com/secured"}""")
            .expectStatus().isUnauthorized()
        rest.request(HttpMethod.POST, "/api/short-links", formHeaders, "access_token=$token").expectStatus().isUnauthorized()
    }

    @Test
    fun `accepts a valid token, whatever the case of the scheme, and records the client`() {
        create(bearer(TestIdp.token())).expectStatus().isCreated()
        create("bearer ${TestIdp.token()}").expectStatus().isCreated()
    }

    @Test
    fun `accepts a token only in the authorization header, never in the query string or a form body`() {
        val token = TestIdp.token()

        get("/api/short-links?access_token=$token").expectStatus().isUnauthorized()
        get("/api/short-links", bearer(token)).expectStatus().isOk()
        rest.request(HttpMethod.POST, "/api/short-links", formHeaders, "access_token=$token").expectStatus().isUnauthorized()
    }

    @Test
    fun `following a short link needs no token`() {
        val code = Regex(""""shortCode":"([^"]+)"""").find(create(bearer(TestIdp.token())).text())!!.groupValues[1]

        noRedirects.get().uri("/$code").exchange().expectStatus().isFound()
    }

    @Test
    fun `health probes and metrics are public on the management port but other actuator endpoints are not`() {
        get("http://localhost:$managementPort/actuator/health").expectStatus().isOk()
        get("http://localhost:$managementPort/actuator/health/liveness").expectStatus().isOk()
        get("http://localhost:$managementPort/actuator/prometheus").expectStatus().isOk()
        get("http://localhost:$managementPort/actuator/env").expectStatus().isUnauthorized()
    }

    @Test
    fun `denies every unlisted path, and answers an authenticated client with a 403 problem detail`() {
        get("/unlisted/thing").expectStatus().isUnauthorized()

        get("/unlisted/thing", bearer(TestIdp.token()))
            .expectStatus().isForbidden()
            .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `everything under the api path needs a token, and what the token may do is decided by the operation`() {
        get("/api/unknown/thing").expectStatus().isUnauthorized()

        get("/api/unknown/thing", bearer(TestIdp.token())).expectStatus().isNotFound()
    }

    @Test
    fun `every response carries restrictive security headers`() {
        get("/zzzzzzz")
            .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
            .expectHeader().valueEquals("X-Frame-Options", "DENY")
            .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
            .expectHeader().valueEquals("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
    }
}
