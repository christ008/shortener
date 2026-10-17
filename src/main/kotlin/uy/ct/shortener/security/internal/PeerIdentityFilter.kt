package uy.ct.shortener.security.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Serves only the callers whose TLS client certificate names an identity in [allowed]: a `403` for any other, and for a request with no certificate.
 *
 * - TLS already proved the certificate comes from the stack's CA. That is not enough: the CA also signs the identities of the migration job and
 *   of the database, and only the edge may call the application.
 * - It runs first in the chain, before the limits, so a refused caller costs nothing but the refusal.
 * - The management port shares the chain and has no TLS, so [onManagementPort] says which requests came in through it, and those are not checked: its probes carry no certificate.
 * - It is installed only when `shortener.security.mtls.allowed-identities` has a name. Without one the application serves every caller, as it did before mutual TLS.
 */
class PeerIdentityFilter(
    private val allowed: Set<String>,
    private val responder: SecurityProblemResponder,
    private val onManagementPort: (HttpServletRequest) -> Boolean = { false },
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest) = onManagementPort(request)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (PeerIdentity.of(request).isAmong(allowed)) {
            chain.doFilter(request, response)
        } else {
            responder.peerNotAllowed(request, response)
        }
    }
}
