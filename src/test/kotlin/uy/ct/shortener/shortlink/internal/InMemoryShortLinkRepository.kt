package uy.ct.shortener.shortlink.internal

import uy.ct.shortener.shortlink.LinkCursor
import uy.ct.shortener.shortlink.LinkOrder
import uy.ct.shortener.shortlink.LinkPage
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository
import java.net.URI
import java.time.Instant

/**
 * In-memory [ShortLinkRepository] for service unit tests, with the same semantics as the real one:
 * insert-if-absent, paging by position in either order and idempotent disabling. Each stored link gets a
 * later creation time than the last, so ordering is deterministic. [seed] pre-populates a taken code,
 * and [lookups] counts how often a link was read by its code.
 */
class InMemoryShortLinkRepository : ShortLinkRepository {

    val saved = mutableListOf<ShortLink>()

    var lookups = 0
        private set

    private var now = Instant.parse("2026-01-01T00:00:00Z")

    fun seed(shortCode: ShortCode, createdBy: String = "seed") {
        insertIfAbsent(shortCode, URI.create("https://seed.example.com"), createdBy)
    }

    override fun findByShortCode(shortCode: ShortCode): ShortLink? {
        lookups++
        return saved.find { it.shortCode == shortCode }
    }

    override fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): ShortLink? {
        if (saved.any { it.shortCode == shortCode }) return null
        now = now.plusSeconds(1)
        return ShortLink(shortCode, targetUrl, createdBy, now).also { saved += it }
    }

    override fun list(createdBy: String?, order: LinkOrder, size: Int, after: LinkCursor?): LinkPage {
        require(size in 1..ShortLinkRepository.MAX_PAGE_SIZE)
        val oldestFirst = compareBy<ShortLink> { it.createdAt }.thenBy { it.shortCode.value }
        val inOrder = if (order == LinkOrder.OLDEST_FIRST) oldestFirst else oldestFirst.reversed()
        val position = after?.let { ShortLink(it.shortCode, URI.create("https://position.example.com"), null, it.createdAt) }
        val rest = saved.filter { createdBy == null || it.createdBy == createdBy }
            .sortedWith(inOrder)
            .filter { position == null || inOrder.compare(it, position) > 0 }
            .take(size + 1)
        val items = rest.take(size)
        return LinkPage(items, if (rest.size > size) items.last().let { LinkCursor(it.createdAt, it.shortCode) } else null)
    }

    override fun disable(shortCode: ShortCode, disabledBy: String): ShortLink? {
        val index = saved.indexOfFirst { it.shortCode == shortCode }
        if (index < 0) return null
        val link = saved[index]
        if (link.isDisabled) return link
        now = now.plusSeconds(1)
        return link.copy(disabledAt = now, disabledBy = disabledBy).also { saved[index] = it }
    }
}
