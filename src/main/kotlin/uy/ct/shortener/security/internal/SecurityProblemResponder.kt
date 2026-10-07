package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * Answers the failures of the security filter chain (`401`, `403`, `429`) as problem details.
 *
 * - Each failure is built as a [SecurityProblem] and handed to MVC's exception resolver.
 * - A `401` carries the challenge, with `error` set to `invalid_token` or `invalid_dpop_proof` when a token or proof was
 *   rejected, or to `use_dpop_nonce` when the proof lacks the current nonce, which is not counted as a failed authentication. A `403` carries `error="insufficient_scope"`.
 * - The challenge names `DPoP` with the accepted proof algorithms (RFC 9449), and `Bearer` as well unless [dpopRequired] or
 *   the request already used one scheme.
 * - A `429` carries `Retry-After`.
 * - Each failure is reported to [events], when there is one, with the OAuth error code and never the exception's message.
 */
class SecurityProblemResponder(
    private val resolver: HandlerExceptionResolver,
    private val dpopRequired: Boolean = false,
    private val events: SecurityEvents? = null,
) : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        val error = (authException as? OAuth2AuthenticationException)?.error?.errorCode
        val nonceNeeded = error == DpopNonceAuthenticationConverter.USE_DPOP_NONCE
        if (nonceNeeded) {
            events?.nonceRequested(request)
        } else {
            events?.unauthenticated(request, error ?: if (AuthScheme.of(request) == AuthScheme.NONE) "missing_credentials" else "invalid_credentials")
        }
        respond(
            request, response,
            SecurityProblem(
                HttpStatus.UNAUTHORIZED,
                if (nonceNeeded) "The DPoP proof needs the current nonce, sent in the DPoP-Nonce header" else "A valid access token is required",
                challenge(AuthScheme.of(request), error),
            ),
        )
    }

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        events?.forbidden(request, SecurityContextHolder.getContext().authentication?.name, OAuth2ErrorCodes.INSUFFICIENT_SCOPE)
        respond(
            request, response,
            SecurityProblem(
                HttpStatus.FORBIDDEN,
                "The access token does not grant access to this resource",
                challenge(AuthScheme.of(request), OAuth2ErrorCodes.INSUFFICIENT_SCOPE),
            ),
        )
    }

    fun tooManyRequests(request: HttpServletRequest, response: HttpServletResponse, retryAfterSeconds: Long, limit: String = "", key: String = "") {
        events?.rateLimited(request, limit, key)
        val headers = HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString()) }
        respond(request, response, SecurityProblem(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded, retry in $retryAfterSeconds seconds", headers))
    }

    private fun respond(request: HttpServletRequest, response: HttpServletResponse, problem: SecurityProblem) {
        response.status = problem.statusCode.value()
        resolver.resolveException(request, response, null, problem)
    }

    private fun challenge(used: AuthScheme, error: String?): HttpHeaders {
        val errorParam = error?.let { """, error="$it"""" }.orEmpty()
        val dpop = """DPoP realm="shortener"$errorParam, algs="$DPOP_ALGORITHMS""""
        val bearer = """Bearer realm="shortener"$errorParam"""
        val challenges = when (used) {
            AuthScheme.DPOP -> listOf(dpop)
            AuthScheme.BEARER -> if (dpopRequired) listOf(dpop) else listOf(bearer)
            AuthScheme.NONE -> if (dpopRequired) listOf(dpop) else listOf(bearer, dpop)
        }
        return HttpHeaders().apply { addAll(HttpHeaders.WWW_AUTHENTICATE, challenges) }
    }

    private companion object {
        const val DPOP_ALGORITHMS = "RS256 RS384 RS512 PS256 PS384 PS512 ES256 ES384 ES512"
    }
}
