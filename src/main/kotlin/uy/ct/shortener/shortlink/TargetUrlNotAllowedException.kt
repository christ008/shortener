package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus

/** This service does not shorten links to the target's host: 400. The message names the host, not the allowed ones. */
class TargetUrlNotAllowedException(host: String) :
    ShortLinkException(HttpStatus.BAD_REQUEST, "Links to '$host' are not accepted by this service")
