package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/** Server's fault (500), not the client's: the code space (62^7) shouldn't realistically exhaust this fast. */
class ShortCodeExhaustionException(attempts: Int) : ErrorResponseException(
    HttpStatus.INTERNAL_SERVER_ERROR,
    ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Could not allocate a unique short code after $attempts attempts"),
    null,
)
