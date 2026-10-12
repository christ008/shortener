package uy.ct.shortener.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.ShortCode
import java.time.Instant

/** The arithmetic of the counts: visitors are people and never bots, and a total is the sum of its points. */
class ClickStatsTest {

    private val window = StatsWindow.of(Granularity.Hour, Instant.parse("2026-10-12T10:00:00Z"), Instant.parse("2026-10-12T12:00:00Z"))

    @Test
    fun `visitors are desktop plus mobile, and never include bots`() {
        val counts = DeviceCounts(desktop = 5, mobile = 3, bot = 11)

        assertThat(counts.visitors).isEqualTo(8)
        assertThat(counts.desktop).isEqualTo(5)
        assertThat(counts.mobile).isEqualTo(3)
        assertThat(counts.bot).isEqualTo(11)
    }

    @Test
    fun `counts add class by class, and nothing is the identity`() {
        val sum = DeviceCounts(1, 2, 3) + DeviceCounts(10, 20, 30)

        assertThat(sum).isEqualTo(DeviceCounts(11, 22, 33))
        assertThat(sum + DeviceCounts.NONE).isEqualTo(sum)
        assertThat(DeviceCounts.NONE.visitors).isZero()
    }

    @Test
    fun `the total is the sum of the points, and nothing for no points`() {
        val points = window.buckets.mapIndexed { index, start -> ClickPoint(start, DeviceCounts(index + 1L, 1, 2)) }

        assertThat(ClickStats(ShortCode("abc1234"), window, points).total).isEqualTo(DeviceCounts(3, 2, 4))
        assertThat(ClickStats(ShortCode("abc1234"), window, emptyList()).total).isEqualTo(DeviceCounts.NONE)
    }
}
