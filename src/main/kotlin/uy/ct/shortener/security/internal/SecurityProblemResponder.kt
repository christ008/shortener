package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.web.servlet.HandlerExceptionResolver

/**
 * Answers the failures that happen in the security filter chain: 401, 403 and 429.
 *
 * - The chain runs before Spring MVC, so it cannot throw to MVC's error handling. Each failure is
 *   built as a [SecurityProblem] and handed to MVC's own exception resolver, which renders it as the
 *   problem detail every other error uses.
 * - A 401 carries the challenge, with the `error` of the failure when a token or proof was rejected
 *   (`invalid_token`, `invalid_dpop_proof`). A 403 carries `error="insufficient_scope"`.
 * - The challenge names `DPoP` with the proof algorithms accepted (RFC 9449), and `Bearer` as well
 *   unless [dpopRequired], or unless the request already used one scheme.
 * - A 429 carries `Retry-After`.
 */
class SecurityProblemResponder(
    private val resolver: HandlerExceptionResolver,
    private val dpopRequired: Boolean = false,
) : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(request: HttpServletRequest, response: HttpServletResponse, authException: AuthenticationException) {
        val error = (authException as? OAuth2AuthenticationException)?.error?.errorCode
        respond(
            request, response,
            SecurityProblem(HttpStatus.UNAUTHORIZED, "A valid access token is required", challenge(AuthScheme.of(request), error)),
        )
    }

    override fun handle(request: HttpServletRequest, response: HttpServletResponse, accessDeniedException: AccessDeniedException) {
        respond(
            request, response,
            SecurityProblem(
                HttpStatus.FORBIDDEN,
                "The access token does not grant access to this resource",
                challenge(AuthScheme.of(request), OAuth2ErrorCodes.INSUFFICIENT_SCOPE),
            ),
        )
    }

    fun tooManyRequests(request: HttpServletRequest, response: HttpServletResponse, retryAfterSeconds: Long) {
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
        val challenges = when {
            used == AuthScheme.DPOP || dpopRequired -> listOf(dpop)
            used == AuthScheme.BEARER -> listOf(bearer)
            else -> listOf(bearer, dpop)
        }
        return HttpHeaders().apply { addAll(HttpHeaders.WWW_AUTHENTICATE, challenges) }
    }

    private companion object {
        const val DPOP_ALGORITHMS = "RS256 RS384 RS512 PS256 PS384 PS512 ES256 ES384 ES512"
    }
}
