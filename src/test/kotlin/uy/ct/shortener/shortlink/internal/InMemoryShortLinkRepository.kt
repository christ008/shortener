package uy.ct.shortener.shortlink.internal

import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository
import java.net.URI
import java.time.Instant

/**
 * In-memory [ShortLinkRepository] for service unit tests, with the same semantics as the real one:
 * insert-if-absent, newest-first paging by offset and idempotent disabling. Each stored link gets a
 * later creation time than the last, so ordering is deterministic. [seed] pre-populates a taken code.
 */
class InMemoryShortLinkRepository : ShortLinkRepository {

    val saved = mutableListOf<ShortLink>()

    private var now = Instant.parse("2026-01-01T00:00:00Z")

    fun seed(shortCode: ShortCode, createdBy: String = "seed") {
        insertIfAbsent(shortCode, URI.create("https://seed.example.com"), createdBy)
    }

    override fun findByShortCode(shortCode: ShortCode): ShortLink? = saved.find { it.shortCode == shortCode }

    override fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): ShortLink? {
        if (findByShortCode(shortCode) != null) return null
        now = now.plusSeconds(1)
        return ShortLink(shortCode, targetUrl, createdBy, now).also { saved += it }
    }

    override fun list(createdBy: String?, pageable: Pageable): Slice<ShortLink> {
        val newestFirst = compareByDescending<ShortLink> { it.createdAt }.thenByDescending { it.shortCode.value }
        val matching = saved.filter { createdBy == null || it.createdBy == createdBy }.sortedWith(newestFirst)
        val page = matching.drop(pageable.offset.toInt()).take(pageable.pageSize + 1)
        return SliceImpl(page.take(pageable.pageSize), pageable, page.size > pageable.pageSize)
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
