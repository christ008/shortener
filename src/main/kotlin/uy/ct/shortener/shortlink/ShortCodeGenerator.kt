package uy.ct.shortener.shortlink

/**
 * Produces candidate [ShortCode]s. A candidate may already be taken, so callers must check.
 */
fun interface ShortCodeGenerator {

    fun generate(): ShortCode
}
