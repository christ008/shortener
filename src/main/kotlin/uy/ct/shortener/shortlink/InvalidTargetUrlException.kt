package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus

/** The target is not an absolute http(s) URL: 400. */
class InvalidTargetUrlException(targetUrl: String, cause: Throwable? = null) :
    ShortLinkException(HttpStatus.BAD_REQUEST, "'$targetUrl' is not a valid absolute http(s) URL", cause)
