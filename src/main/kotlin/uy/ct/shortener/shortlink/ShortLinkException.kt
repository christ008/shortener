package uy.ct.shortener.shortlink

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * Base of every failure the API reports as a problem detail (RFC 9457).
 *
 * - Extends Spring's [ErrorResponseException], so Spring MVC renders it with no handler of our own.
 * - Carries its HTTP [status] and a [detail] message, plus response [headers] when the client needs them.
 * - Sealed: the failures are exactly the subclasses in this package, so a `when` over them is checked for
 *   completeness and no outside code can add a failure the API contract does not describe.
 */
sealed class ShortLinkException(
    status: HttpStatus,
    detail: String,
    cause: Throwable? = null,
    private val responseHeaders: HttpHeaders = HttpHeaders(),
) : ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), cause) {

    override fun getHeaders(): HttpHeaders = responseHeaders
}
