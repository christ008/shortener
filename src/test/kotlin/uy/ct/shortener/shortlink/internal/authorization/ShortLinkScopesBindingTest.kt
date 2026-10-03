package uy.ct.shortener.shortlink.internal.authorization

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration
import org.springframework.context.annotation.Configuration

/**
 * The scope names bind from `shortener.shortlink.scopes.*` and keep their defaults when unset,
 * but a blank name stops the application from starting, because it would leave an operation
 * unreachable or open to the wrong tokens.
 */
class ShortLinkScopesBindingTest {

    @Configuration
    @EnableConfigurationProperties(ShortLinkScopes::class)
    class Binding

    private val runner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration::class.java))
        .withUserConfiguration(Binding::class.java)

    @Test
    fun `binds configured scope names and keeps the defaults for the rest`() {
        runner.withPropertyValues("shortener.shortlink.scopes.admin=links.admin").run {
            val scopes = it.getBean(ShortLinkScopes::class.java)

            assertThat(scopes.admin).isEqualTo("links.admin")
            assertThat(scopes.create).isEqualTo("shortlinks:create")
        }
    }

    @Test
    fun `refuses to start with a blank scope name`() {
        runner.withPropertyValues("shortener.shortlink.scopes.read=").run {
            assertThat(it).hasFailed()
        }
    }
}
