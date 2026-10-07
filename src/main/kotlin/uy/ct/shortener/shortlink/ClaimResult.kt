package uy.ct.shortener.shortlink

/**
 * The outcome of claiming a chosen code for a target: the link was [Created], or the caller already had exactly this link and
 * it is [Existing]. Claiming again is how a client that lost the answer finds out what happened.
 */
sealed interface ClaimResult {

    val link: ShortLink

    data class Created(override val link: ShortLink) : ClaimResult

    /** The caller's own link under this code, for this target, as it is now: it may have been disabled since. */
    data class Existing(override val link: ShortLink) : ClaimResult
}
