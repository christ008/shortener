package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * The short link exists but has been disabled. Rendered as an HTTP 410 problem detail. The code
 * stays taken, so nobody else can register it.
 */
class ShortLinkDisabledException(shortCode: ShortCode) : ErrorResponseException(
    HttpStatus.GONE,
    ProblemDetail.forStatusAndDetail(HttpStatus.GONE, "The short link '$shortCode' has been disabled"),
    null,
)
