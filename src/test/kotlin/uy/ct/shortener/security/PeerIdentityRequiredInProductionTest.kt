package uy.ct.shortener.security

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment
import uy.ct.shortener.security.internal.SecurityProperties

/**
 * Mutual TLS has two halves, and `production` does not start with only one of them.
 *
 * - Asking callers for a certificate without naming who may call serves every certificate the CA signed, not only the edge's.
 * - Naming who may call without asking for a certificate refuses every request.
 * - Asking for nothing and naming no one starts, and so does both.
 * - Other profiles are left alone, so that tests and development can use one half.
 */
class PeerIdentityRequiredInProductionTest {

    private val configuration = SecurityConfiguration()

    private val edge = "spiffe://shortener.internal/prod/edge"

    private fun production(clientAuth: String? = null) = MockEnvironment().apply {
        setActiveProfiles("production")
        clientAuth?.let { setProperty("server.ssl.client-auth", it) }
    }

    private fun identities(vararg names: String) = SecurityProperties(mtls = SecurityProperties.Mtls(names.toList()))

    @Test
    fun `production refuses a certificate asked for with no identity named, and says what to set`() {
        for (mode in listOf("need", "NEED", "want")) {
            assertThatThrownBy { configuration.requirePeerIdentityToMatchClientAuth(identities(), production(mode)) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("shortener.security.mtls.allowed-identities")
                .hasMessageContaining("SHORTENER_SECURITY_MTLS_ALLOWEDIDENTITIES")
        }
    }

    @Test
    fun `production refuses an identity named with no certificate asked for`() {
        for (clientAuth in listOf(null, "none")) {
            assertThatThrownBy { configuration.requirePeerIdentityToMatchClientAuth(identities(edge), production(clientAuth)) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("SERVER_SSL_CLIENTAUTH")
        }
    }

    @Test
    fun `production starts with both halves, and with neither`() {
        assertThatCode { configuration.requirePeerIdentityToMatchClientAuth(identities(edge), production("need")) }.doesNotThrowAnyException()
        assertThatCode { configuration.requirePeerIdentityToMatchClientAuth(SecurityProperties(), production()) }.doesNotThrowAnyException()
        assertThatCode { configuration.requirePeerIdentityToMatchClientAuth(SecurityProperties(), production("none")) }.doesNotThrowAnyException()
    }

    @Test
    fun `other profiles may have one half, for development and tests`() {
        assertThatCode { configuration.requirePeerIdentityToMatchClientAuth(identities(edge), MockEnvironment()) }.doesNotThrowAnyException()
        val dev = MockEnvironment().apply { setActiveProfiles("dev"); setProperty("server.ssl.client-auth", "need") }
        assertThatCode { configuration.requirePeerIdentityToMatchClientAuth(identities(), dev) }.doesNotThrowAnyException()
    }
}
