package uy.ct.shortener.analytics.internal.persistence

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.analytics.Granularity
import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.analytics.internal.ClickRow
import uy.ct.shortener.analytics.internal.DeviceClass
import uy.ct.shortener.shortlink.ShortCode
import java.time.Instant

/**
 * [JdbcClickStatsRepository] against real Postgres with the Flyway schema applied: hourly rows become hour or day buckets, a
 * window is half open, and another link's rows are never read.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class, JdbcClickStatsRepository::class)
class JdbcClickStatsRepositoryTest {

    @Autowired
    lateinit var repository: JdbcClickStatsRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private fun at(text: String) = Instant.parse(text)

    private fun insert(code: String, hour: String, device: String, clicks: Long) {
        jdbc.update("INSERT INTO link_click_hourly (short_code, hour, device_class, clicks) VALUES (?, ?::timestamptz, ?, ?)", code, hour, device, clicks)
    }

    @BeforeEach
    fun rows() {
        insert("stats-a", "2026-10-12T10:00:00Z", "desktop", 5)
        insert("stats-a", "2026-10-12T10:00:00Z", "bot", 2)
        insert("stats-a", "2026-10-12T11:00:00Z", "desktop", 3)
        insert("stats-a", "2026-10-12T11:00:00Z", "mobile", 4)
        insert("stats-a", "2026-10-13T01:00:00Z", "mobile", 6)
        insert("stats-b", "2026-10-12T10:00:00Z", "desktop", 100)
    }

    private fun hours(from: String, to: String) = StatsWindow.of(Granularity.Hour, at(from), at(to))

    private fun days(from: String, to: String) = StatsWindow.of(Granularity.Day, at(from), at(to))

    @Test
    fun `reads each hour and class of the link inside the window`() {
        val rows = repository.read(ShortCode("stats-a"), hours("2026-10-12T10:00:00Z", "2026-10-12T12:00:00Z"))

        assertThat(rows).containsExactlyInAnyOrder(
            ClickRow(at("2026-10-12T10:00:00Z"), DeviceClass.DESKTOP, 5),
            ClickRow(at("2026-10-12T10:00:00Z"), DeviceClass.BOT, 2),
            ClickRow(at("2026-10-12T11:00:00Z"), DeviceClass.DESKTOP, 3),
            ClickRow(at("2026-10-12T11:00:00Z"), DeviceClass.MOBILE, 4),
        )
    }

    @Test
    fun `adds the hours of a day into one bucket per class`() {
        val rows = repository.read(ShortCode("stats-a"), days("2026-10-12T00:00:00Z", "2026-10-14T00:00:00Z"))

        assertThat(rows).containsExactlyInAnyOrder(
            ClickRow(at("2026-10-12T00:00:00Z"), DeviceClass.DESKTOP, 8),
            ClickRow(at("2026-10-12T00:00:00Z"), DeviceClass.BOT, 2),
            ClickRow(at("2026-10-12T00:00:00Z"), DeviceClass.MOBILE, 4),
            ClickRow(at("2026-10-13T00:00:00Z"), DeviceClass.MOBILE, 6),
        )
    }

    @Test
    fun `a day is a day in UTC, whatever the session's time zone`() {
        jdbc.execute("SET TIME ZONE 'America/Montevideo'")

        val rows = repository.read(ShortCode("stats-a"), days("2026-10-13T00:00:00Z", "2026-10-14T00:00:00Z"))

        assertThat(rows).containsExactly(ClickRow(at("2026-10-13T00:00:00Z"), DeviceClass.MOBILE, 6))
    }

    @Test
    fun `the start is included and the end is not`() {
        val rows = repository.read(ShortCode("stats-a"), hours("2026-10-12T11:00:00Z", "2026-10-12T12:00:00Z"))

        assertThat(rows.map { it.bucket }.toSet()).containsExactly(at("2026-10-12T11:00:00Z"))
    }

    @Test
    fun `never reads another link's rows`() {
        val rows = repository.read(ShortCode("stats-b"), days("2026-10-12T00:00:00Z", "2026-10-13T00:00:00Z"))

        assertThat(rows).containsExactly(ClickRow(at("2026-10-12T00:00:00Z"), DeviceClass.DESKTOP, 100))
    }

    @Test
    fun `a link or a window with no rows reads none`() {
        assertThat(repository.read(ShortCode("never-seen"), days("2026-10-12T00:00:00Z", "2026-10-13T00:00:00Z"))).isEmpty()
        assertThat(repository.read(ShortCode("stats-a"), days("2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z"))).isEmpty()
    }
}
