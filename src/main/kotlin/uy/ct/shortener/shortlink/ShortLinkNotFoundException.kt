package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus

/** No link exists under the code, or the caller may not see it: 404. */
class ShortLinkNotFoundException(shortCode: ShortCode) :
    ShortLinkException(HttpStatus.NOT_FOUND, "No short link found for code '$shortCode'")
