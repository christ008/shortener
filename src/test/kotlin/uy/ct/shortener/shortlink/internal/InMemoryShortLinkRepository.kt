package uy.ct.shortener.shortlink.internal

import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository
import java.net.URI
import java.time.Instant

/**
 * In-memory [ShortLinkRepository] for service unit tests, with the same insert-if-absent
 * semantics as the real one. [seed] pre-populates a taken code.
 */
class InMemoryShortLinkRepository : ShortLinkRepository {

    val saved = mutableListOf<ShortLink>()

    fun seed(shortCode: ShortCode) {
        insertIfAbsent(shortCode, URI.create("https://seed.example.com"), "seed")
    }

    override fun findByShortCode(shortCode: ShortCode): ShortLink? = saved.find { it.shortCode == shortCode }

    override fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): ShortLink? {
        if (findByShortCode(shortCode) != null) return null
        return ShortLink(shortCode, targetUrl, createdBy, Instant.now()).also { saved += it }
    }
}
