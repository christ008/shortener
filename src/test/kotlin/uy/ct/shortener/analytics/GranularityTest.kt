package uy.ct.shortener.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * What differs between an hour and a day as a bucket of clicks: where a moment's bucket starts, where the next one does, how wide
 * a window may be and how wide it is when none is asked for. The variants are the whole of the type, so the tests go through all.
 */
class GranularityTest {

    private val moment = Instant.parse("2026-10-12T10:47:31.250Z")

    @Test
    fun `an hour begins on the hour and a day at midnight UTC`() {
        assertThat(Granularity.Hour.floor(moment)).isEqualTo(Instant.parse("2026-10-12T10:00:00Z"))
        assertThat(Granularity.Day.floor(moment)).isEqualTo(Instant.parse("2026-10-12T00:00:00Z"))
    }

    @Test
    fun `a boundary is its own start`() {
        Granularity.all.forEach { assertThat(it.floor(it.floor(moment))).describedAs(it.wire).isEqualTo(it.floor(moment)) }
    }

    @Test
    fun `the next bucket begins one unit later`() {
        assertThat(Granularity.Hour.next(Instant.parse("2026-10-12T23:00:00Z"))).isEqualTo(Instant.parse("2026-10-13T00:00:00Z"))
        assertThat(Granularity.Day.next(Instant.parse("2026-10-31T00:00:00Z"))).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"))
    }

    @Test
    fun `an hour may span 90 days and a day 366, and each answers a shorter window when none is asked for`() {
        assertThat(Granularity.Hour.maxSpan).isEqualTo(Duration.ofDays(90))
        assertThat(Granularity.Day.maxSpan).isEqualTo(Duration.ofDays(366))
        assertThat(Granularity.Hour.defaultSpan).isEqualTo(Duration.ofHours(48))
        assertThat(Granularity.Day.defaultSpan).isEqualTo(Duration.ofDays(30))
        Granularity.all.forEach { assertThat(it.defaultSpan).describedAs(it.wire).isLessThanOrEqualTo(it.maxSpan) }
    }

    @Test
    fun `its name in the API is what date_trunc takes, and parses back to it`() {
        Granularity.all.forEach {
            assertThat(it.unit).isEqualTo(it.wire)
            assertThat(Granularity.parse(it.wire)).isSameAs(it)
        }
        assertThat(Granularity.all).containsExactly(Granularity.Hour, Granularity.Day)
    }

    @Test
    fun `refuses a name it does not know and lists the ones it does`() {
        listOf("week", "Hour", "", " day").forEach { name ->
            val failure = assertFailsWith<InvalidStatsWindowException> { Granularity.parse(name) }

            assertThat(failure.statusCode.value()).isEqualTo(400)
            assertThat(failure.body.detail).contains("'hour'", "'day'")
        }
    }
}
