package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * The submitted target is not an absolute http(s) URL. Rendered as an HTTP 400 problem detail.
 */
class InvalidTargetUrlException(targetUrl: String, cause: Throwable? = null) : ErrorResponseException(
    HttpStatus.BAD_REQUEST,
    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "'$targetUrl' is not a valid absolute http(s) URL"),
    cause,
)
