package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * The requested sort is not one links can be listed in. Links are listed by creation time only, because that is the
 * order a position in the listing can be named in (see [LinkCursor]) and the one the indexes serve. Rendered as an
 * HTTP 400 problem detail that says which sorts are allowed.
 */
class InvalidSortException(requested: String) : ErrorResponseException(
    HttpStatus.BAD_REQUEST,
    ProblemDetail.forStatusAndDetail(
        HttpStatus.BAD_REQUEST,
        "Cannot sort by '$requested'; links are listed by createdAt only, as createdAt,desc (newest first, the default) or createdAt,asc",
    ),
    null,
)
