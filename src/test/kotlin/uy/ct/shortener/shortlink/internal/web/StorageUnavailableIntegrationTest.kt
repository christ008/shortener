package uy.ct.shortener.shortlink.internal.web

import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
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
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class StorageUnavailableIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var registry: MeterRegistry

    @LocalManagementPort
    var managementPort: Int = 0

    private fun redirect(code: String) = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).getForEntity("/$code", String::class.java)

    private fun management(path: String) = restTemplate.getForEntity("http://localhost:$managementPort/actuator/health/$path", String::class.java)

    private fun create() = restTemplate.exchange(
        "/api/short-links",
        HttpMethod.POST,
        HttpEntity(
            """{"targetUrl":"https://example.com/unavailable"}""",
            HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(TestIdp.token())
            },
        ),
        String::class.java,
    )

    @Test
    fun `answers 503 with Retry-After while every connection is busy, then recovers`() {
        dataSource.connection.use {
            val lookup = restTemplate.getForEntity("/zzzzzzz", String::class.java)

            assertThat(lookup.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(lookup.headers.contentType).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON)
            assertThat(lookup.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5")
            assertThat(lookup.body).contains(""""status":503""")

            val creation = create()

            assertThat(creation.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(creation.headers.getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5")
        }

        assertThat(restTemplate.getForEntity("/zzzzzzz", String::class.java).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(create().statusCode).isEqualTo(HttpStatus.CREATED)
    }

    @Test
    fun `keeps redirecting the links it has read, and stays ready, while storage is unavailable`() {
        val code = Regex(""""shortCode":"([^"]+)"""").find(create().body!!)!!.groupValues[1]
        assertThat(redirect(code).statusCode).isEqualTo(HttpStatus.FOUND)
        Thread.sleep(1_200)
        val before = registry.get("shortlink.redirect.cache.stale").counter().count()

        dataSource.connection.use {
            val stale = redirect(code)
            val unknown = restTemplate.getForEntity("/zzzzzzz", String::class.java)

            assertThat(stale.statusCode).describedAs("a link it read a moment ago").isEqualTo(HttpStatus.FOUND)
            assertThat(stale.headers.location.toString()).startsWith("https://example.com/")
            assertThat(unknown.statusCode).describedAs("a code it has never read").isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            assertThat(management("readiness").statusCode).describedAs("readiness while storage is down").isEqualTo(HttpStatus.OK)
            assertThat(management("liveness").statusCode).isEqualTo(HttpStatus.OK)
        }

        assertThat(registry.get("shortlink.redirect.cache.stale").counter().count()).isEqualTo(before + 1)
        assertThat(redirect(code).statusCode).describedAs("once storage is back").isEqualTo(HttpStatus.FOUND)
    }
}
