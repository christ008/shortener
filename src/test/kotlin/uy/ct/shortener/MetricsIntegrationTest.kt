package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.http.client.HttpRedirects
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkRepository
import java.net.URI

/**
 * What Prometheus scrapes from the management port: request latency as a histogram with the
 * route template and status as tags, so short codes never become label values, plus the
 * connection pool, the JVM and the redirect cache. The metrics are not served on the public port.
 */
@WithTestIdp
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration::class)
class MetricsIntegrationTest {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var repository: ShortLinkRepository

    @LocalManagementPort
    var managementPort: Int = 0

    private fun scrape() =
        restTemplate.getForEntity("http://localhost:$managementPort/actuator/prometheus", String::class.java).body!!

    @Test
    fun `exposes request latency histograms tagged by route template, the pool and the jvm`() {
        restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW).getForEntity("/unknownCode", String::class.java)

        val metrics = scrape()
        val serverRequests = metrics.lines().filter { it.startsWith("http_server_requests") }

        assertThat(metrics).contains("http_server_requests_seconds_bucket", "uri=\"/{shortCode}\"", "application=\"shortener\"")
        assertThat(metrics).contains("hikaricp_connections_active", "jvm_memory_used_bytes")
        assertThat(serverRequests).isNotEmpty.noneMatch { it.contains("unknownCode") }
    }

    @Test
    fun `counts redirects served from the cache as hits`() {
        repository.insertIfAbsent(ShortCode("cacheMe"), URI.create("https://example.com/cached"), "metrics-test")
        val client = restTemplate.withRedirects(HttpRedirects.DONT_FOLLOW)

        repeat(3) { assertThat(client.getForEntity("/cacheMe", Void::class.java).statusCode).isEqualTo(HttpStatus.FOUND) }

        val hits = scrape().lines().first { it.startsWith("cache_gets_total") && it.contains("shortlink.redirect") && it.contains("result=\"hit\"") }
        assertThat(hits.substringAfterLast(' ').toDouble()).isGreaterThanOrEqualTo(2.0)
    }

    @Test
    fun `does not serve metrics on the public port`() {
        val response = restTemplate.getForEntity("/actuator/prometheus", String::class.java)

        assertThat(response.statusCode).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.NOT_FOUND)
    }
}
