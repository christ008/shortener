package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus

/** No unique generated code was found within the allowed attempts: 500. */
class ShortCodeExhaustionException(attempts: Int) :
    ShortLinkException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not allocate a unique short code after $attempts attempts")
