package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * Provides the click counting: a [ClickAggregator] and the [ClickFlusher] that writes it, or [NoClickRecorder] when
 * `shortener.analytics.enabled` is false.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AnalyticsProperties::class)
class AnalyticsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun analyticsClock(): Clock = Clock.systemUTC()

    @Bean
    @ConditionalOnProperty("shortener.analytics.enabled", havingValue = "true", matchIfMissing = true)
    fun clickAggregator(properties: AnalyticsProperties, clock: Clock, registry: MeterRegistry): ClickAggregator =
        ClickAggregator(
            properties.maxEntries,
            clock,
            ReferrerAdmission(properties.referrers.perLinkAndDay, properties.referrers.tracked, registry),
            registry,
        )

    @Bean
    @ConditionalOnProperty("shortener.analytics.enabled", havingValue = "true", matchIfMissing = true)
    fun clickFlusher(properties: AnalyticsProperties, aggregator: ClickAggregator, store: ClickStore, registry: MeterRegistry): ClickFlusher =
        ClickFlusher(aggregator, store, properties.batchSize, properties.flushInterval, registry)

    @Bean
    @ConditionalOnProperty("shortener.analytics.enabled", havingValue = "false")
    fun noClickRecorder(): ClickRecorder = NoClickRecorder
}
