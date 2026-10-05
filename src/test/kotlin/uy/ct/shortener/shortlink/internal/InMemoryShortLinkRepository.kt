package uy.ct.shortener.shortlink.internal

import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.data.domain.SliceImpl
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.CreatedByFilter
import uy.ct.shortener.shortlink.InsertResult
import uy.ct.shortener.shortlink.LinkLookup
import uy.ct.shortener.shortlink.LinkStatus
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository
import java.net.URI
import java.time.Instant

/**
 * In-memory [ShortLinkRepository] for service unit tests, with the semantics of the real one.
 *
 * - Insert-if-absent, newest-first paging by offset and idempotent disabling.
 * - Each stored link gets a later creation time than the last, so ordering is deterministic.
 * - [seed] pre-populates a taken code. [lookups] counts how often a link was read by its code.
 */
class InMemoryShortLinkRepository : ShortLinkRepository {

    val saved = mutableListOf<ShortLink>()

    var lookups = 0
        private set

    private var now = Instant.parse("2026-01-01T00:00:00Z")

    fun seed(shortCode: ShortCode, createdBy: String = "seed") {
        insertIfAbsent(shortCode, URI.create("https://seed.example.com"), createdBy)
    }

    override fun findByShortCode(shortCode: ShortCode): LinkLookup {
        lookups++
        return saved.find { it.shortCode == shortCode }?.let { LinkLookup.Found(it) } ?: LinkLookup.Missing
    }

    override fun insertIfAbsent(shortCode: ShortCode, targetUrl: URI, createdBy: String): InsertResult {
        if (saved.any { it.shortCode == shortCode }) return InsertResult.Taken
        now = now.plusSeconds(1)
        return InsertResult.Created(ShortLink(shortCode, targetUrl, Actor.Client(createdBy), now).also { saved += it })
    }

    override fun list(filter: CreatedByFilter, pageable: Pageable): Slice<ShortLink> {
        val newestFirst = compareByDescending<ShortLink> { it.createdAt }.thenByDescending { it.shortCode.value }
        val matching = saved.filter { filter == CreatedByFilter.Anyone || filter.isLimitedTo((it.createdBy as? Actor.Client)?.name.orEmpty()) }
            .sortedWith(newestFirst)
        val page = matching.drop(pageable.offset.toInt()).take(pageable.pageSize + 1)
        return SliceImpl(page.take(pageable.pageSize), pageable, page.size > pageable.pageSize)
    }

    override fun disable(shortCode: ShortCode, disabledBy: String): LinkLookup {
        val index = saved.indexOfFirst { it.shortCode == shortCode }
        if (index < 0) return LinkLookup.Missing
        val link = saved[index]
        if (link.isDisabled) return LinkLookup.Found(link)
        now = now.plusSeconds(1)
        return LinkLookup.Found(link.copy(status = LinkStatus.Disabled(now, Actor.Client(disabledBy))).also { saved[index] = it })
    }
}
