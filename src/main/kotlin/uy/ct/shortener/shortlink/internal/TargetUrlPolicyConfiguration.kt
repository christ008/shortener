package uy.ct.shortener.shortlink.internal

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Provides the [TargetUrlPolicy] that [TargetUrlProperties] describe. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TargetUrlProperties::class)
class TargetUrlPolicyConfiguration {

    @Bean
    fun targetUrlPolicy(properties: TargetUrlProperties): TargetUrlPolicy = properties.toPolicy()
}
