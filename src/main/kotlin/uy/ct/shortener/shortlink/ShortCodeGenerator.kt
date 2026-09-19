package uy.ct.shortener.shortlink

fun interface ShortCodeGenerator {
    fun generate(): ShortCode
}
