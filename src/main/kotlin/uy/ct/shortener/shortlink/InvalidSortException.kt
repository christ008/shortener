package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * The requested sort property is not one links can be listed by. Rendered as an HTTP 400 problem
 * detail naming the properties that are allowed.
 */
class InvalidSortException(property: String, allowed: Collection<String>) : ErrorResponseException(
    HttpStatus.BAD_REQUEST,
    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Cannot sort by '$property'; sortable properties are ${allowed.sorted().joinToString()}"),
    null,
)
