package uy.ct.shortener.shortlink.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * The allowlist binds from a comma separated value, which is how an environment variable carries a list, and an empty
 * value means no restriction rather than a host called "": a stack that leaves the variable unset must accept targets.
 */
class TargetUrlPropertiesBindingTest {

    @Configuration
    @EnableConfigurationProperties(TargetUrlProperties::class)
    class Binding

    private val runner = ApplicationContextRunner().withUserConfiguration(Binding::class.java)

    @Test
    fun `binds a comma separated list of hosts`() {
        runner.withPropertyValues("shortener.shortlink.target-urls.allowed-hosts=example.com, *.example.org").run {
            assertThat(it.getBean(TargetUrlProperties::class.java).allowedHosts).containsExactly("example.com", "*.example.org")
        }
    }

    @Test
    fun `binds the environment variable the stack sets, with an empty value as no restriction`() {
        fun bound(value: String): TargetUrlProperties {
            val environment = StandardEnvironment()
            environment.propertySources.addFirst(
                SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, mapOf("SHORTENER_SHORTLINK_TARGETURLS_ALLOWEDHOSTS" to value)),
            )
            // An empty value binds to nothing, and the properties then keep their defaults, as they do in the application.
            return Binder.get(environment).bind("shortener.shortlink.target-urls", TargetUrlProperties::class.java)
                .orElseGet { TargetUrlProperties() }
        }

        assertThat(bound("example.com,*.example.org").allowedHosts).containsExactly("example.com", "*.example.org")
        assertThat(bound("").toPolicy()).isSameAs(AnyTarget)
    }

    @Test
    fun `an empty value accepts every host`() {
        runner.withPropertyValues("shortener.shortlink.target-urls.allowed-hosts=").run {
            val properties = it.getBean(TargetUrlProperties::class.java)

            assertThat(properties.allowedHosts).isEmpty()
            assertThat(properties.toPolicy()).isSameAs(AnyTarget)
        }
    }

    @Test
    fun `nothing set accepts every host`() {
        runner.run { assertThat(it.getBean(TargetUrlProperties::class.java).toPolicy()).isSameAs(AnyTarget) }
    }
}
