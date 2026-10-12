package uy.ct.shortener.analytics.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertFailsWith

/** The settings of the click counting: sensible defaults, and a refusal to start with a value that would not work. */
class AnalyticsPropertiesTest {

    @Test
    fun `counts by default, every ten seconds, in batches of 500, holding 50,000 counts`() {
        val defaults = AnalyticsProperties()

        assertThat(defaults).isEqualTo(AnalyticsProperties(true, Duration.ofSeconds(10), 50_000, 500))
        assertThat(defaults.enabled).isTrue()
        assertThat(defaults.maxEntries).isEqualTo(50_000)
    }

    @Test
    fun `accepts the smallest values that work`() {
        val smallest = AnalyticsProperties(flushInterval = Duration.ofMillis(1), maxEntries = 1, batchSize = 1)

        assertThat(smallest.maxEntries).isEqualTo(1)
        assertThat(smallest.batchSize).isEqualTo(1)
    }

    @Test
    fun `refuses an interval that is not positive`() {
        assertFailsWith<IllegalArgumentException> { AnalyticsProperties(flushInterval = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { AnalyticsProperties(flushInterval = Duration.ofSeconds(-1)) }
    }

    @Test
    fun `refuses a cap that holds nothing`() {
        assertFailsWith<IllegalArgumentException> { AnalyticsProperties(maxEntries = 0) }
    }

    @Test
    fun `refuses a batch that is empty or larger than the cap`() {
        assertFailsWith<IllegalArgumentException> { AnalyticsProperties(batchSize = 0) }
        assertFailsWith<IllegalArgumentException> { AnalyticsProperties(maxEntries = 10, batchSize = 11) }
        assertThat(AnalyticsProperties(maxEntries = 10, batchSize = 10).batchSize).isEqualTo(10)
    }
}
