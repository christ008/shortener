package uy.ct.shortener.security.internal

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * A security failure (401, 403 or 429) as a problem detail (RFC 9457) with the response headers it
 * needs, such as `WWW-Authenticate` or `Retry-After`. Spring MVC renders it, like any
 * [ErrorResponseException], once the [SecurityProblemResponder] hands it to the exception resolver.
 */
class SecurityProblem(
    status: HttpStatus,
    detail: String,
    private val responseHeaders: HttpHeaders,
) : ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null) {

    override fun getHeaders(): HttpHeaders = responseHeaders
}
