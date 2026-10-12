package uy.ct.shortener.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * A window is whole buckets, never empty and never wider than its granularity allows. A request is rounded outward to the
 * buckets that hold it, so the answer says what it really covers.
 */
class StatsWindowTest {

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `a request is widened to the buckets that hold it`() {
        val window = StatsWindow.of(Granularity.Hour, at("2026-10-12T10:47:00Z"), at("2026-10-12T12:10:00Z"))

        assertThat(window.start).isEqualTo(at("2026-10-12T10:00:00Z"))
        assertThat(window.end).isEqualTo(at("2026-10-12T13:00:00Z"))
        assertThat(window.buckets).containsExactly(at("2026-10-12T10:00:00Z"), at("2026-10-12T11:00:00Z"), at("2026-10-12T12:00:00Z"))
    }

    @Test
    fun `an end that is a boundary is not widened, because it is not included`() {
        val window = StatsWindow.of(Granularity.Day, at("2026-10-10T00:00:00Z"), at("2026-10-12T00:00:00Z"))

        assertThat(window.buckets).containsExactly(at("2026-10-10T00:00:00Z"), at("2026-10-11T00:00:00Z"))
    }

    @Test
    fun `a window inside one bucket is that bucket`() {
        val window = StatsWindow.of(Granularity.Day, at("2026-10-12T09:00:00Z"), at("2026-10-12T10:00:00Z"))

        assertThat(window.buckets).containsExactly(at("2026-10-12T00:00:00Z"))
    }

    @Test
    fun `days are told apart in UTC`() {
        val window = StatsWindow.of(Granularity.Day, at("2026-10-12T23:59:59Z"), at("2026-10-13T00:00:01Z"))

        assertThat(window.buckets).containsExactly(at("2026-10-12T00:00:00Z"), at("2026-10-13T00:00:00Z"))
    }

    @Test
    fun `refuses a window that is empty or runs backwards`() {
        listOf(
            at("2026-10-12T10:00:00Z") to at("2026-10-12T10:00:00Z"),
            at("2026-10-12T10:00:00Z") to at("2026-10-12T09:00:00Z"),
        ).forEach { (from, to) ->
            val failure = assertFailsWith<InvalidStatsWindowException> { StatsWindow.of(Granularity.Hour, from, to) }

            assertThat(failure.statusCode.value()).isEqualTo(400)
            assertThat(failure.body.detail).contains("end after it begins")
        }
    }

    @Test
    fun `allows exactly the widest window and refuses a bucket more, and says how wide it may be`() {
        val start = at("2026-01-01T00:00:00Z")

        assertThat(StatsWindow.of(Granularity.Hour, start, start.plusSeconds(90L * 24 * 3600)).buckets).hasSize(90 * 24)
        assertThat(StatsWindow.of(Granularity.Day, start, start.plusSeconds(366L * 24 * 3600)).buckets).hasSize(366)

        val hours = assertFailsWith<InvalidStatsWindowException> { StatsWindow.of(Granularity.Hour, start, start.plusSeconds(90L * 24 * 3600 + 1)) }
        assertThat(hours.body.detail).contains("at most 90 days", "hours")
        val days = assertFailsWith<InvalidStatsWindowException> { StatsWindow.of(Granularity.Day, start, start.plusSeconds(367L * 24 * 3600)) }
        assertThat(days.body.detail).contains("at most 366 days", "days")
    }

    @Test
    fun `a window built by hand must sit on boundaries`() {
        val failure = assertFailsWith<InvalidStatsWindowException> {
            StatsWindow(Granularity.Hour, at("2026-10-12T10:30:00Z"), at("2026-10-12T12:00:00Z"))
        }

        assertThat(failure.body.detail).contains("boundary")
        assertFailsWith<InvalidStatsWindowException> { StatsWindow(Granularity.Hour, at("2026-10-12T10:00:00Z"), at("2026-10-12T12:30:00Z")) }
        assertFailsWith<InvalidStatsWindowException> { StatsWindow(Granularity.Day, at("2026-10-12T01:00:00Z"), at("2026-10-13T00:00:00Z")) }
    }

    @Test
    fun `counts are told apart by their buckets in order, none missing`() {
        val window = StatsWindow.of(Granularity.Hour, at("2026-10-12T22:00:00Z"), at("2026-10-13T02:00:00Z"))

        assertThat(window.buckets).containsExactly(
            at("2026-10-12T22:00:00Z"), at("2026-10-12T23:00:00Z"), at("2026-10-13T00:00:00Z"), at("2026-10-13T01:00:00Z"),
        )
    }
}
