package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus

/** The link exists but was disabled, and its code stays taken: 410. */
class ShortLinkDisabledException(shortCode: ShortCode) :
    ShortLinkException(HttpStatus.GONE, "The short link '$shortCode' has been disabled")
