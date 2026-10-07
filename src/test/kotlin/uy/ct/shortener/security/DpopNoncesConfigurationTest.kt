package uy.ct.shortener.security

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import uy.ct.shortener.security.internal.NoDpopNonces
import uy.ct.shortener.security.internal.SecurityProperties

/**
 * Two instances behind one edge answer for the same clients, so a nonce one made must be good at the other. A random secret for
 * each process would send clients from one `use_dpop_nonce` to the next, so `production` does not start without a shared secret,
 * unless nonces or DPoP are turned off on purpose.
 */
class DpopNoncesConfigurationTest {

    private val configuration = SecurityConfiguration()

    private fun production() = MockEnvironment().apply { setActiveProfiles("production") }

    private fun properties(required: Boolean = true, nonceEnabled: Boolean = true, secret: String = "") =
        SecurityProperties(dpop = SecurityProperties.Dpop(required, SecurityProperties.Nonce(enabled = nonceEnabled, secret = secret)))

    @Test
    fun `production refuses to start with nonces on and no secret, and says what to set`() {
        assertThatThrownBy { configuration.dpopNonces(properties(), production()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("shortener.security.dpop.nonce.secret")
    }

    @Test
    fun `production starts with a secret`() {
        val nonces = configuration.dpopNonces(properties(secret = "shared"), production())

        assertThat(nonces.accepts(nonces.current())).isTrue()
    }

    @Test
    fun `production starts without a secret when nonces or DPoP are turned off on purpose`() {
        assertThat(configuration.dpopNonces(properties(nonceEnabled = false), production())).isSameAs(NoDpopNonces)
        assertThat(configuration.dpopNonces(properties(required = false), production()).current()).isNotNull()
    }

    @Test
    fun `other profiles may go without a secret, for a single instance`() {
        val nonces = configuration.dpopNonces(properties(), MockEnvironment())

        assertThat(nonces.accepts(nonces.current())).isTrue()
    }
}
