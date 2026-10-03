package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * No short link exists for the requested code. Rendered as an HTTP 404 problem detail.
 */
class ShortLinkNotFoundException(shortCode: ShortCode) : ErrorResponseException(
    HttpStatus.NOT_FOUND,
    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No short link found for code '$shortCode'"),
    null,
)
