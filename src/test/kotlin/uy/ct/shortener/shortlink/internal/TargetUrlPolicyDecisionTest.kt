package uy.ct.shortener.shortlink.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * A production instance must say which targets it accepts, so that an open redirector is chosen and not forgotten.
 * Anywhere else, saying nothing still accepts every host.
 */
class TargetUrlPolicyDecisionTest {

    private val runner = ApplicationContextRunner().withUserConfiguration(TargetUrlPolicyConfiguration::class.java)

    private val production = "spring.profiles.active=production"

    @Test
    fun `production without a list or an explicit choice does not start`() {
        runner.withPropertyValues(production).run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).hasStackTraceContaining("is not decided")
        }
    }

    @Test
    fun `production with a list starts and accepts only those hosts`() {
        runner.withPropertyValues(production, "shortener.shortlink.target-urls.allowed-hosts=example.com").run {
            assertThat(it.getBean(TargetUrlPolicy::class.java)).isInstanceOf(AllowedHosts::class.java)
        }
    }

    @Test
    fun `production starts open only when asked to`() {
        runner.withPropertyValues(production, "shortener.shortlink.target-urls.allow-any=true").run {
            assertThat(it.getBean(TargetUrlPolicy::class.java)).isSameAs(AnyTarget)
        }
    }

    @Test
    fun `a list and allow-any together are refused, whatever the profile`() {
        runner.withPropertyValues("shortener.shortlink.target-urls.allowed-hosts=example.com", "shortener.shortlink.target-urls.allow-any=true").run {
            assertThat(it).hasFailed()
        }
    }

    @Test
    fun `without the production profile nothing set accepts every host`() {
        runner.run { assertThat(it.getBean(TargetUrlPolicy::class.java)).isSameAs(AnyTarget) }
    }

    @Test
    fun `an empty value from the environment is no decision`() {
        runner.withPropertyValues(production, "shortener.shortlink.target-urls.allowed-hosts=").run { assertThat(it).hasFailed() }
    }
}
