package uy.ct.shortener.shortlink.internal

import uy.ct.shortener.shortlink.LinkLookup
import uy.ct.shortener.shortlink.ShortCode

/**
 * The lookup a redirect makes for each request, with or without a cache in front of the database.
 *
 * - [CaffeineRedirectCache] keeps active links in memory.
 * - [NoRedirectCache] is the disabled cache: it keeps nothing and always loads.
 */
interface RedirectCache {

    /** Returns the link under [shortCode], calling [load] when the cache cannot answer. */
    fun find(shortCode: ShortCode, load: (ShortCode) -> LinkLookup): LinkLookup

    /** Forgets the link under [shortCode], as soon as it is disabled. */
    fun evict(shortCode: ShortCode)
}

/** A [RedirectCache] that never caches: it keeps nothing and always loads. Used when the cache is turned off. */
object NoRedirectCache : RedirectCache {

    override fun find(shortCode: ShortCode, load: (ShortCode) -> LinkLookup): LinkLookup = load(shortCode)

    override fun evict(shortCode: ShortCode) = Unit
}
