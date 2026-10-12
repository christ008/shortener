package uy.ct.shortener.analytics.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import uy.ct.shortener.RestTestClientSupport
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.WithTestIdp
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkRepository
import java.net.URI

/**
 * Clicks from the public redirect to a row in the database: followed by `GET`, classified by the `User-Agent`, counted in memory
 * and written when the flusher runs, with nothing counted for a `HEAD` or an unknown code. The aggregator and the flusher are the
 * application's own beans, and the flush is called by hand so the test does not wait for the timer.
 */
@WithTestIdp
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration::class)
class AnalyticsIntegrationTest {

    @LocalServerPort
    var port: Int = 0

    private val rest by lazy { RestTestClientSupport.withoutRedirects(port) }

    @Autowired
    lateinit var repository: ShortLinkRepository

    @Autowired
    lateinit var flusher: ClickFlusher

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun clicks(code: String) = jdbc.queryForList("SELECT device_class, sum(clicks) AS clicks FROM link_click_hourly WHERE short_code = ? GROUP BY 1", code)
        .associate { it["device_class"] as String to (it["clicks"] as Number).toLong() }

    @Test
    fun `counts the redirects a link served, by class of device, once they are flushed`() {
        repository.insertIfAbsent(ShortCode("clickMe"), URI.create("https://example.com/clicked"), "analytics-test")

        repeat(3) { rest.get().uri("/clickMe").header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Chrome/130.0").exchange().expectStatus().isFound() }
        rest.get().uri("/clickMe").header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0) Mobile/15E148").exchange().expectStatus().isFound()
        rest.get().uri("/clickMe").header("User-Agent", "Slackbot-LinkExpanding 1.0").exchange().expectStatus().isFound()
        flusher.flush()

        assertThat(clicks("clickMe")).containsOnly(
            entry("desktop", 3L),
            entry("mobile", 1L),
            entry("bot", 1L),
        )
    }

    @Test
    fun `counts nothing for a HEAD, an unknown code or a link that was disabled`() {
        repository.insertIfAbsent(ShortCode("notCounted"), URI.create("https://example.com/not-counted"), "analytics-test")
        repository.insertIfAbsent(ShortCode("gone"), URI.create("https://example.com/gone"), "analytics-test")
        repository.disable(ShortCode("gone"), "analytics-test")

        rest.head().uri("/notCounted").exchange().expectStatus().isFound()
        rest.get().uri("/neverExisted").exchange().expectStatus().isNotFound()
        rest.get().uri("/gone").exchange().expectStatus().isEqualTo(410)
        flusher.flush()

        assertThat(clicks("notCounted")).isEmpty()
        assertThat(clicks("neverExisted")).isEmpty()
        assertThat(clicks("gone")).isEmpty()
    }
}
