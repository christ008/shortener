package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/** Extends [ErrorResponseException] so Spring MVC renders it as a `ProblemDetail` with no extra `@ControllerAdvice` needed. */
class ShortLinkNotFoundException(shortCode: ShortCode) : ErrorResponseException(
    HttpStatus.NOT_FOUND,
    ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No short link found for code '$shortCode'"),
    null,
)
