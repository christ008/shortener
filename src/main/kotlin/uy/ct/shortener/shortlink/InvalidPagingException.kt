package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * The request names a position in the listing that cannot be used: a cursor that is not one the service issued or
 * that belongs to another sort, or the `page` parameter, which listing no longer has. Rendered as an HTTP 400
 * problem detail.
 */
class InvalidPagingException(detail: String) : ErrorResponseException(
    HttpStatus.BAD_REQUEST,
    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail),
    null,
)
