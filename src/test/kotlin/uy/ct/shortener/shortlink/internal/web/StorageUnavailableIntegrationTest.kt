package uy.ct.shortener.shortlink.internal.web

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
import uy.ct.shortener.RestTestClientSupport
import uy.ct.shortener.RestTestClientSupport.expectStatus
import uy.ct.shortener.RestTestClientSupport.request
import uy.ct.shortener.RestTestClientSupport.text
import uy.ct.shortener.TestIdp
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import javax.sql.DataSource

/**
 * When no database connection can be had, requests are answered with a 503 problem detail and
 * `Retry-After` instead of a 500, and the service recovers on its own once a connection is free.
 * Redirects of links the instance has already read carry on from its last known copy of them for a while after
 * their cache entry expires, and the instance stays ready: it is the same outage for every pod, and taking them
 * all out of rotation would fail the redirects they can still serve. The pool is shrunk to one connection with a
 * short timeout, and the test holds that connection.
 */
@WithTestIdp
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.connection-timeout=250",
        "shortener.shortlink.redirect-cache.ttl=1s",
        "shortener.shortlink.redirect-cache.stale-if-error=1m",
    ],
)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class StorageUnavailableIntegrationTest {

    @Autowired
    lateinit var rest: RestTestClient

    @LocalServerPort
    var port: Int = 0

    private val noRedirects by lazy { RestTestClientSupport.withoutRedirects(port) }

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var registry: MeterRegistry

    @LocalManagementPort
    var managementPort: Int = 0

    private fun redirect(code: String) = noRedirects.get().uri("/$code").exchange()

    private fun management(path: String) = rest.get().uri("http://localhost:$managementPort/actuator/health/$path").exchange()

    private fun create() = rest.request(
        HttpMethod.POST,
        "/api/short-links",
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            setBearerAuth(TestIdp.token())
        },
        """{"targetUrl":"https://example.com/unavailable"}""",
    )

    @Test
    fun `answers 503 with Retry-After while every connection is busy, then recovers`() {
        dataSource.connection.use {
            rest.get().uri("/zzzzzzz").exchange()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
                .expectBody(String::class.java).value { assertThat(it).contains(""""status":503""") }

            create()
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
        }

        rest.get().uri("/zzzzzzz").exchange().expectStatus().isNotFound()
        create().expectStatus().isCreated()
    }

    @Test
    fun `keeps redirecting the links it has read, and stays ready, while storage is unavailable`() {
        val code = Regex(""""shortCode":"([^"]+)"""").find(create().text())!!.groupValues[1]
        redirect(code).expectStatus().isFound()
        Thread.sleep(1_200)
        val before = registry.get("shortlink.redirect.cache.stale").counter().count()

        dataSource.connection.use {
            redirect(code)
                .expectStatus("a link it read a moment ago", HttpStatus.FOUND)
                .expectHeader().value(HttpHeaders.LOCATION) { assertThat(it).startsWith("https://example.com/") }
            rest.get().uri("/zzzzzzz").exchange().expectStatus("a code it has never read", HttpStatus.SERVICE_UNAVAILABLE)
            management("readiness").expectStatus("readiness while storage is down", HttpStatus.OK)
            management("liveness").expectStatus().isOk()
        }

        assertThat(registry.get("shortlink.redirect.cache.stale").counter().count()).isEqualTo(before + 1)
        redirect(code).expectStatus("once storage is back", HttpStatus.FOUND)
    }
}
