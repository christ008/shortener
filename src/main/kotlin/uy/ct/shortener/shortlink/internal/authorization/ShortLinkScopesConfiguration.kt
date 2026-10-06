package uy.ct.shortener.shortlink.internal.authorization

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Binds [ShortLinkScopes] and makes it available under the name method-security expressions use, `@scopes`.
 *
 * Constructor-bound properties cannot be components, which is how a bean gets a name of its own, so the name is given
 * here. It is the same object, and it is the primary one, so injection by type finds exactly one.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortLinkScopes::class)
class ShortLinkScopesConfiguration {

    @Bean("scopes")
    @Primary
    fun scopes(properties: ShortLinkScopes): ShortLinkScopes = properties
}
