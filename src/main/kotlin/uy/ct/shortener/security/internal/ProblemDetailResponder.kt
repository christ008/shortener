package uy.ct.shortener.security.internal

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler

/**
 * Writes 401, 403 and 429 answers as RFC 9457 problem details, matching the rest of the API.
 * A 401 carries a `Bearer` challenge, with `error="invalid_token"` when a key was presented but
 * rejected (RFC 6750). A 429 carries `Retry-After`.
 */
class ProblemDetailResponder : AuthenticationEntryPoint, AccessDeniedHandler {

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val challenge = if (authException is BadCredentialsException) {
            """Bearer realm="shortener", error="invalid_token""""
        } else {
            """Bearer realm="shortener""""
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge)
        write(response, HttpStatus.UNAUTHORIZED, "A valid API key is required")
    }

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) = write(response, HttpStatus.FORBIDDEN, "Access is denied")

    fun tooManyRequests(response: HttpServletResponse, retryAfterSeconds: Long) {
        response.setHeader(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
        write(response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded, retry in $retryAfterSeconds seconds")
    }

    private fun write(response: HttpServletResponse, status: HttpStatus, detail: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.outputStream.write(
            """{"type":"about:blank","title":"${status.reasonPhrase}","status":${status.value()},"detail":"$detail"}"""
                .toByteArray(Charsets.UTF_8),
        )
    }
}
