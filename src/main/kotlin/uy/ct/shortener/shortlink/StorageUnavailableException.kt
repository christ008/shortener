package uy.ct.shortener.shortlink

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus

/** Storage cannot serve the request right now, for example when the database is unreachable or every connection is busy: `503` with `Retry-After`. */
class StorageUnavailableException(cause: Throwable) : ShortLinkException(
    HttpStatus.SERVICE_UNAVAILABLE,
    "The service is temporarily unable to reach its storage, retry shortly",
    cause,
    HttpHeaders().apply { set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS.toString()) },
) {

    companion object {
        const val RETRY_AFTER_SECONDS = 5
    }
}
