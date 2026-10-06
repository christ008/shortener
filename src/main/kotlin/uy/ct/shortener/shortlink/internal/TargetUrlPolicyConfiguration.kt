package uy.ct.shortener.shortlink.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles

/**
 * Provides the [TargetUrlPolicy] that [TargetUrlProperties] describe.
 *
 * - Under the `production` profile the settings must list hosts or set [TargetUrlProperties.allowAny], or the application does not start.
 * - The profile is read when the bean is created, not through a condition (native image: docs/INTERNALS.md).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TargetUrlProperties::class)
class TargetUrlPolicyConfiguration {

    @Bean
    fun targetUrlPolicy(properties: TargetUrlProperties, environment: Environment): TargetUrlPolicy {
        check(properties.isDecided || !environment.acceptsProfiles(Profiles.of(PRODUCTION))) {
            "Which hosts links may point to is not decided. Set shortener.shortlink.target-urls.allowed-hosts " +
                "(SHORTENER_SHORTLINK_TARGETURLS_ALLOWEDHOSTS), or allow-any=true to accept every host."
        }
        LoggerFactory.getLogger(TargetUrlPolicyConfiguration::class.java).info(
            "Link targets: {}",
            if (properties.allowedHosts.isEmpty()) "every host is accepted" else "${properties.allowedHosts.size} allowed host(s)",
        )
        return properties.toPolicy()
    }

    private companion object {
        const val PRODUCTION = "production"
    }
}
