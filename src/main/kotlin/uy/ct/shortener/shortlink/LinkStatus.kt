package uy.ct.shortener.shortlink

import java.time.Instant

/**
 * Whether a link redirects. A link starts [Active] and can move once to [Disabled], which is final.
 */
sealed interface LinkStatus {

    data object Active : LinkStatus

    /** Disabled at [at] by [by]. */
    data class Disabled(val at: Instant, val by: Actor) : LinkStatus
}
