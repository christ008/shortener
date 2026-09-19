package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/** Client's fault (400): the submitted URL isn't a parseable, absolute http(s) address. */
class InvalidTargetUrlException(targetUrl: String, cause: Throwable? = null) : ErrorResponseException(
    HttpStatus.BAD_REQUEST,
    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "'$targetUrl' is not a valid absolute http(s) URL"),
    cause,
)
