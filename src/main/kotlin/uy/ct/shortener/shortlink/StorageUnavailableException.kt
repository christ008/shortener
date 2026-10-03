package uy.ct.shortener.shortlink

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException

/**
 * Storage cannot serve the request right now, for example because the database is unreachable
 * or every connection is busy. Rendered as an HTTP 503 problem detail with `Retry-After`, since
 * the condition is usually momentary and the client should try again.
 */
class StorageUnavailableException(cause: Throwable) : ErrorResponseException(
    HttpStatus.SERVICE_UNAVAILABLE,
    ProblemDetail.forStatusAndDetail(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The service is temporarily unable to reach its storage, retry shortly",
    ),
    cause,
) {

    override fun getHeaders(): HttpHeaders = HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString()) }

    companion object {
        const val RETRY_AFTER_SECONDS = 5
    }
}
