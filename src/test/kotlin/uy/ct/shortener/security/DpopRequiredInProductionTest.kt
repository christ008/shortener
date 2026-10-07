package uy.ct.shortener.security

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import uy.ct.shortener.security.internal.SecurityProperties

/**
 * Plain bearer tokens can be replayed by anyone who sees one, which is what binding them to a key is for. `production` says DPoP is
 * required in its own file, but an environment variable that says otherwise wins over a file, so the application checks the value it
 * ends up with and does not start with it off.
 */
class DpopRequiredInProductionTest {

    private val configuration = SecurityConfiguration()

    private fun production() = MockEnvironment().apply { setActiveProfiles("production") }

    private fun properties(required: Boolean) = SecurityProperties(dpop = SecurityProperties.Dpop(required))

    @Test
    fun `production refuses to start with DPoP off, and says which setting to unset`() {
        assertThatThrownBy { configuration.requireDpopInProduction(properties(required = false), production()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("shortener.security.dpop.required")
            .hasMessageContaining("SHORTENER_SECURITY_DPOP_REQUIRED")
            .hasMessageContaining("bearer")
    }

    @Test
    fun `production starts with DPoP required, which is the default`() {
        assertThatCode { configuration.requireDpopInProduction(properties(required = true), production()) }.doesNotThrowAnyException()
        assertThatCode { configuration.requireDpopInProduction(SecurityProperties(), production()) }.doesNotThrowAnyException()
    }

    @Test
    fun `other profiles may turn it off, for development and tests`() {
        assertThatCode { configuration.requireDpopInProduction(properties(required = false), MockEnvironment()) }.doesNotThrowAnyException()
        val dev = MockEnvironment().apply { setActiveProfiles("dev") }
        assertThatCode { configuration.requireDpopInProduction(properties(required = false), dev) }.doesNotThrowAnyException()
    }

    @Test
    fun `a profile list that includes production is production`() {
        val both = MockEnvironment().apply { setActiveProfiles("dev", "production") }

        assertThatThrownBy { configuration.requireDpopInProduction(properties(required = false), both) }.isInstanceOf(IllegalStateException::class.java)
    }
}
