package uy.ct.shortener.shortlink

import org.springframework.http.HttpStatus

/** The listing was sorted by a property that is not sortable, which the message names: 400. */
class InvalidSortException(property: String, allowed: Collection<String>) : ShortLinkException(
    HttpStatus.BAD_REQUEST,
    "Cannot sort by '$property'; sortable properties are ${allowed.sorted().joinToString()}",
)
