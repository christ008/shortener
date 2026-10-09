package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.Environment

/**
 * What each Spring profile changes. The profile files are loaded on their own, without starting the
 * application: `dev` turns everything on for visibility, `production` hardens what a deployment shows
 * and structures its logs, and `test` is what every other test runs under. A setting that is not
 * mentioned here is the same in all of them.
 */
class ProfilesTest {

    private fun under(profile: String, check: (Environment) -> Unit) {
        ApplicationContextRunner()
            .withInitializer(ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.profiles.active=$profile")
            .run { check(it.environment) }
    }

    @Test
    fun `dev samples every trace, shows health details and relaxes the rate limits`() = under("dev") { env ->
        assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("1.0")
        assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("always")
        assertThat(env.getProperty("logging.level.uy.ct.shortener")).isEqualTo("DEBUG")
        assertThat(env.getProperty("shortener.security.rate-limit.per-ip.capacity")).isEqualTo("10000")
    }

    @Test
    fun `production structures its logs, hides internals and samples 5 percent of traces`() = under("production") { env ->
        assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("ecs")
        assertThat(env.getProperty("management.endpoint.health.show-details")).isEqualTo("never")
        assertThat(env.getProperty("spring.web.error.include-stacktrace")).isEqualTo("never")
        assertThat(env.getProperty("spring.web.error.include-message")).isEqualTo("never")
        assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("0.05")
        assertThat(env.getProperty("shortener.security.dpop.required")).isEqualTo("true")
    }

    @Test
    fun `test accepts plain bearer tokens and traces nothing`() = under("test") { env ->
        assertThat(env.getProperty("shortener.security.dpop.required")).isEqualTo("false")
        assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("0.0")
    }

    @Test
    fun `no profile at all leaves the quiet defaults, with no tracing and no relaxed limits`() = under("none") { env ->
        assertThat(env.getProperty("management.tracing.sampling.probability")).isEqualTo("0.0")
        assertThat(env.getProperty("logging.structured.format.console")).isNull()
        assertThat(env.getProperty("shortener.security.rate-limit.per-ip.capacity")).isNull()
    }

    @Test
    fun `the identity provider and exporter addresses stay in the base file for every profile, because a native image needs them when it is built`() {
        listOf("dev", "production", "test").forEach { profile ->
            under(profile) { env ->
                assertThat(env.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri")).isNotBlank
                assertThat(env.getProperty("management.opentelemetry.tracing.export.otlp.endpoint")).isNotBlank
            }
        }
    }
}
