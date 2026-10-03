package uy.ct.shortener.security.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates requests carrying `Authorization: Bearer <api key>`. A valid key authenticates
 * the request as its client name. A request without a bearer token continues unauthenticated,
 * so the authorization rules decide. A presented key that is not recognised is rejected
 * immediately with a 401.
 */
class BearerApiKeyAuthenticationFilter(
    private val authenticator: ApiKeyAuthenticator,
    private val entryPoint: AuthenticationEntryPoint,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val key = request.getHeader(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith(BEARER_PREFIX, ignoreCase = true) }
            ?.substring(BEARER_PREFIX.length)
            ?.trim()
        if (key == null) {
            chain.doFilter(request, response)
            return
        }

        val client = authenticator.authenticate(key)
        if (client == null) {
            entryPoint.commence(request, response, BadCredentialsException("Unknown API key"))
            return
        }
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken.authenticated(client, null, AuthorityUtils.createAuthorityList("ROLE_API_CLIENT"))
        chain.doFilter(request, response)
    }

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
