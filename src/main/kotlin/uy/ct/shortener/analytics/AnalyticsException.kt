package uy.ct.shortener.analytics

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * Base of every failure the analytics API reports as a problem detail (RFC 9457).
 *
 * - Carries its HTTP status and a detail message that names what the caller asked for and what is allowed.
 * - Sealed: the failures are the subclasses in this package. A failure of the link itself (not found, storage down) is the
 *   `shortlink` module's own and passes through unchanged.
 */
sealed class AnalyticsException(status: HttpStatus, detail: String) :
    ErrorResponseException(status, ProblemDetail.forStatusAndDetail(status, detail), null)

/** The window asked for cannot be answered, and the message says why: 400. */
class InvalidStatsWindowException(detail: String) : AnalyticsException(HttpStatus.BAD_REQUEST, detail)
