package uy.ct.shortener.security.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Puts the current nonce in the `DPoP-Nonce` header of every response to a request under the `DPoP` scheme, whatever its status,
 * so a client learns the nonce from the `401` that asks for it and a new one from the answers that follow (RFC 9449, section 9).
 * Does nothing when [nonces] hands none out.
 */
class DpopNonceFilter(private val nonces: DpopNonces) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (AuthScheme.of(request) == AuthScheme.DPOP) nonces.current()?.let { response.setHeader(HEADER, it) }
        chain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "DPoP-Nonce"
    }
}
