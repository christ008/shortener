package uy.ct.shortener.shortlink.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the [RedirectCache] from its [RedirectCacheProperties] and the application's meter registry. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedirectCacheProperties::class)
class RedirectCacheConfiguration {

    @Bean
    fun redirectCache(properties: RedirectCacheProperties, registry: MeterRegistry) = RedirectCache(properties, registry)
}
