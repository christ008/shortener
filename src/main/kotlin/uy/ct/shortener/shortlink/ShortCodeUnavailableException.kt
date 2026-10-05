package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus

/** The chosen code is taken or reserved: 409. */
class ShortCodeUnavailableException(shortCode: ShortCode) :
    ShortLinkException(HttpStatus.CONFLICT, "Short code '$shortCode' is not available")
