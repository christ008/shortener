package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * Pins how API keys reach the application from a Kubernetes Secret. Spring drops dashes from
 * environment variable names instead of turning them into underscores, so the property
 * `shortener.security.api-keys` is set by `SHORTENER_SECURITY_APIKEYS_<CLIENT>`.
 * `SHORTENER_SECURITY_API_KEYS_<CLIENT>` looks equivalent but silently binds nothing.
 */
class SecurityPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(SecurityProperties::class)
    class Properties

    private fun bind(environment: Map<String, Any>): SecurityProperties {
        var bound: SecurityProperties? = null
        ApplicationContextRunner()
            .withUserConfiguration(Properties::class.java)
            .withInitializer {
                it.environment.propertySources.addFirst(
                    SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environment),
                )
            }
            .run { bound = it.getBean(SecurityProperties::class.java) }
        return bound!!
    }

    @Test
    fun `binds api keys from environment variables named like the kubernetes secret keys`() {
        val properties = bind(mapOf("SHORTENER_SECURITY_APIKEYS_CI" to "abc", "SHORTENER_SECURITY_APIKEYS_MOBILE" to "def"))

        assertThat(properties.apiKeys).containsExactlyInAnyOrderEntriesOf(mapOf("ci" to "abc", "mobile" to "def"))
    }

    @Test
    fun `does not bind the underscore spelling of the property name`() {
        assertThat(bind(mapOf("SHORTENER_SECURITY_API_KEYS_CI" to "abc")).apiKeys).isEmpty()
    }

    @Test
    fun `binds rate limits from environment variables, keeping the default period`() {
        val properties = bind(mapOf("SHORTENER_SECURITY_RATELIMIT_PERKEY_CAPACITY" to "5"))

        assertThat(properties.rateLimit.perKey.capacity).isEqualTo(5)
        assertThat(properties.rateLimit.perKey.period.toMinutes()).isEqualTo(1)
        assertThat(properties.rateLimit.perClient.capacity).isEqualTo(300)
    }
}
