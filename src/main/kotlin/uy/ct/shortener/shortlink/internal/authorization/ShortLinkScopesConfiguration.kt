package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/** Binds [ShortLinkScopes] and exposes it as the primary bean `scopes`. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortLinkScopes::class)
class ShortLinkScopesConfiguration {

    @Bean("scopes")
    @Primary
    fun scopes(properties: ShortLinkScopes): ShortLinkScopes = properties
}
