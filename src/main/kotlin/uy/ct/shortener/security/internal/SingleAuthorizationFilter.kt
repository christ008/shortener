package uy.ct.shortener.security.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.web.filter.OncePerRequestFilter
import java.util.Collections

/**
 * Refuses a request to the API that presents its access token in more than one way, such as a `Bearer` and a `DPoP`
 * `Authorization` header: `invalid_request` and a `400` (RFC 9449, section 7.2, and RFC 6750, section 3.1).
 *
 * It runs before the filters that read the header, which would otherwise take the first and answer a `401` about it.
 */
class SingleAuthorizationFilter(private val responder: SecurityProblemResponder) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION)).size > 1) {
            responder.commence(
                request, response,
                OAuth2AuthenticationException(OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST, "Multiple methods used to include the access token", null)),
            )
            return
        }
        chain.doFilter(request, response)
    }
}
