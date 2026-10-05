package uy.ct.shortener.shortlink

/**
 * The answer to "is there a link under this code": a [Found] link or [Missing], never null.
 */
sealed interface LinkLookup {

    data class Found(val link: ShortLink) : LinkLookup

    data object Missing : LinkLookup

    /**
     * Returns the link, or throws [ShortLinkNotFoundException] for [shortCode] when [Missing].
     */
    fun orThrow(shortCode: ShortCode): ShortLink = when (this) {
        is Found -> link
        Missing -> throw ShortLinkNotFoundException(shortCode)
    }
}
