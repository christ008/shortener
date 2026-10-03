package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * The requested custom short code is already taken or reserved. Rendered as an HTTP 409 problem detail.
 */
class ShortCodeUnavailableException(shortCode: ShortCode) : ErrorResponseException(
    HttpStatus.CONFLICT,
    ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Short code '$shortCode' is not available"),
    null,
)
