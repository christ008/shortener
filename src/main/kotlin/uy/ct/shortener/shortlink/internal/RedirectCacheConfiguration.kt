package uy.ct.shortener.shortlink.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Provides the [RedirectCache]: a [CaffeineRedirectCache], or [NoRedirectCache] when it is turned off. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedirectCacheProperties::class)
class RedirectCacheConfiguration {

    @Bean
    fun redirectCache(properties: RedirectCacheProperties, registry: MeterRegistry): RedirectCache =
        if (properties.enabled) CaffeineRedirectCache(properties, registry) else NoRedirectCache
}
