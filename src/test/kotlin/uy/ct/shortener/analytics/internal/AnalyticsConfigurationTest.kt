package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * What `shortener.analytics.enabled` switches: the aggregator and the flusher that writes it, or the recorder that keeps nothing.
 * The listener is there either way, so the application publishes the same event in both.
 */
class AnalyticsConfigurationTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(AnalyticsConfiguration::class.java, Collaborators::class.java, ClickListener::class.java)

    @Configuration(proxyBeanMethods = false)
    class Collaborators {

        @Bean
        fun registry(): MeterRegistry = SimpleMeterRegistry()

        @Bean
        fun store(): ClickStore = object : ClickStore {
            override fun add(counts: List<ClickCount>) = Unit
        }
    }

    @Test
    fun `counts and flushes by default`() {
        runner.run { context ->
            assertThat(context.getBean(ClickRecorder::class.java)).isInstanceOf(ClickAggregator::class.java)
            assertThat(context).hasSingleBean(ClickFlusher::class.java).hasSingleBean(ClickListener::class.java)
        }
    }

    @Test
    fun `counts nothing, and flushes nothing, when it is switched off`() {
        runner.withPropertyValues("shortener.analytics.enabled=false").run { context ->
            assertThat(context.getBean(ClickRecorder::class.java)).isSameAs(NoClickRecorder)
            assertThat(context).doesNotHaveBean(ClickFlusher::class.java).doesNotHaveBean(ClickAggregator::class.java)
            assertThat(context).hasSingleBean(ClickListener::class.java)
        }
    }

    @Test
    fun `takes its sizes from the settings`() {
        runner.withPropertyValues("shortener.analytics.max-entries=7", "shortener.analytics.batch-size=3", "shortener.analytics.flush-interval=2s").run { context ->
            assertThat(context.getBean(AnalyticsProperties::class.java)).isEqualTo(AnalyticsProperties(true, Duration.ofSeconds(2), 7, 3))
        }
    }

    @Test
    fun `does not start with a setting that cannot work`() {
        runner.withPropertyValues("shortener.analytics.batch-size=0").run { context ->
            assertThat(context).hasFailed()
        }
    }
}
