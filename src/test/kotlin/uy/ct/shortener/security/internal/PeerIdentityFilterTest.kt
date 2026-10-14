package uy.ct.shortener.security.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerExceptionResolver
import org.springframework.web.servlet.ModelAndView
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The identity a caller's TLS certificate names, and who the application serves because of it.
 *
 * - The certificates are the edge's and the migration job's, both issued by the stack's CA: the CA's signature is not what decides.
 * - Only a certificate with an allowed URI name is passed on. Another identity, a certificate with no URI name and no certificate at all are a `403`.
 * - A certificate that lists several URI names is served when any of them is allowed.
 * - A request that came in through the management port is not checked, whoever it is.
 */
class PeerIdentityFilterTest {

    private val edge = "spiffe://shortener.internal/prod/edge"

    private val problems = mutableListOf<SecurityProblem>()

    private val responder = SecurityProblemResponder(
        object : HandlerExceptionResolver {
            override fun resolveException(request: HttpServletRequest, response: HttpServletResponse, handler: Any?, ex: Exception): ModelAndView? {
                problems += ex as SecurityProblem
                return null
            }
        },
        events = SecurityEvents(SimpleMeterRegistry()),
    )

    private val filter = PeerIdentityFilter(setOf(edge), responder)

    private fun certificate(name: String): X509Certificate =
        javaClass.getResourceAsStream("/mtls/$name.pem")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private fun call(vararg chain: Any): MockFilterChain {
        val request = MockHttpServletRequest("GET", "/abc1234")
        if (chain.isNotEmpty()) request.setAttribute(PeerIdentity.CERTIFICATE_ATTRIBUTE, chain)
        val filterChain = MockFilterChain()
        filter.doFilter(request, MockHttpServletResponse(), filterChain)
        return filterChain
    }

    @Test
    fun `the edge's certificate is passed on`() {
        assertThat(call(certificate("edge")).request).isNotNull()
        assertThat(problems).isEmpty()
    }

    @Test
    fun `another identity of the same CA is a 403, however valid its certificate`() {
        assertThat(call(certificate("migrator")).request).isNull()
        assertThat(problems.single().statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a request with no client certificate is a 403`() {
        assertThat(call().request).isNull()
        assertThat(problems.single().statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a certificate with only a DNS name has no identity`() {
        assertThat(call(certificate("dns-only")).request).isNull()
        assertThat(PeerIdentity.of(MockHttpServletRequest().apply { setAttribute(PeerIdentity.CERTIFICATE_ATTRIBUTE, arrayOf(certificate("dns-only"))) }))
            .isEqualTo(PeerIdentity.Unidentified)
    }

    @Test
    fun `a certificate that lists several URI names is passed on when one is allowed`() {
        assertThat(call(certificate("two-uris")).request).isNotNull()
    }

    @Test
    fun `a request through the management port is passed on with no certificate`() {
        val request = MockHttpServletRequest("GET", "/actuator/health/readiness").apply { localPort = 8081 }
        val chain = MockFilterChain()
        PeerIdentityFilter(setOf(edge), responder) { it.localPort == 8081 }.doFilter(request, MockHttpServletResponse(), chain)
        assertThat(chain.request).isNotNull()
        assertThat(problems).isEmpty()
    }

    @Test
    fun `an attribute that is not a certificate chain has no identity`() {
        val request = MockHttpServletRequest().apply { setAttribute(PeerIdentity.CERTIFICATE_ATTRIBUTE, "edge") }
        assertThat(PeerIdentity.of(request)).isEqualTo(PeerIdentity.Unidentified)
    }
}
