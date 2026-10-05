package uy.ct.shortener.shortlink

/**
 * The outcome of storing a link under a code: it was [Created], or the code was already [Taken].
 */
sealed interface InsertResult {

    data class Created(val link: ShortLink) : InsertResult

    data object Taken : InsertResult
}
