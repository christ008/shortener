package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * Writes 401, 403 and 429 answers as RFC 9457 problem details, matching the rest of the API.
 * A 401 carries the authentication challenge, with the error of the failure when a token or proof
 * was presented but rejected (`invalid_token`, `invalid_dpop_proof`), and a 403 carries
 * `error="insufficient_scope"`. The challenge names the `DPoP` scheme with the signature
 * algorithms accepted for proofs (RFC 9449), and the `Bearer` scheme as well unless
 * [dpopRequired]. A 429 carries `Retry-After`.
 */
class ProblemDetailResponder(private val dpopRequired: Boolean = false) : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val error = (authException as? OAuth2AuthenticationException)?.error?.errorCode
        challenge(response, error, schemeOf(request))
        write(response, HttpStatus.UNAUTHORIZED, "A valid access token is required")
    }

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        challenge(response, OAuth2ErrorCodes.INSUFFICIENT_SCOPE, schemeOf(request))
        write(response, HttpStatus.FORBIDDEN, "The access token does not grant access to this resource")
    }

    fun tooManyRequests(response: HttpServletResponse, retryAfterSeconds: Long) {
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        write(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded, retry in $retryAfterSeconds seconds")
    }

    private fun challenge(response: HttpServletResponse, error: String?, usedScheme: String?) {
        val errorParam = error?.let { """, error="$it"""" }.orEmpty()
        val dpop = """DPoP realm="shortener"$errorParam, algs="$DPOP_ALGORITHMS""""
        val bearer = """Bearer realm="shortener"$errorParam"""
        when {
            usedScheme == DPOP -> response.addHeader(HttpHeaders.WWW_AUTHENTICATE, dpop)
            dpopRequired -> response.addHeader(HttpHeaders.WWW_AUTHENTICATE, dpop)
            usedScheme == BEARER -> response.addHeader(HttpHeaders.WWW_AUTHENTICATE, bearer)
            else -> {
                response.addHeader(HttpHeaders.WWW_AUTHENTICATE, bearer)
                response.addHeader(HttpHeaders.WWW_AUTHENTICATE, dpop)
            }
        }
    }

    private fun schemeOf(request: HttpServletRequest): String? =
        request.getHeader(HttpHeaders.AUTHORIZATION)?.substringBefore(' ')?.let {
            when {
                it.equals(DPOP, ignoreCase = true) -> DPOP
                it.equals(BEARER, ignoreCase = true) -> BEARER
                else -> null
            }
        }

    private fun write(response: HttpServletResponse, status: HttpStatus, detail: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.outputStream.write(
            """{"type":"about:blank","title":"${status.reasonPhrase}","status":${status.value()},"detail":"$detail"}"""
                .toByteArray(Charsets.UTF_8),
        )
    }

    private companion object {
        const val DPOP = "DPoP"
        const val BEARER = "Bearer"
        const val DPOP_ALGORITHMS = "RS256 RS384 RS512 PS256 PS384 PS512 ES256 ES384 ES512"
    }
}
