package uy.ct.shortener.shortlink.internal

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.stereotype.Service
import uy.ct.shortener.shortlink.CreatedByFilter
import uy.ct.shortener.shortlink.InsertResult
import uy.ct.shortener.shortlink.InvalidTargetUrlException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeExhaustionException
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortCodeUnavailableException
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkDisabledException
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.ShortLinkService
import uy.ct.shortener.shortlink.internal.authorization.ManageableLinks
import uy.ct.shortener.shortlink.internal.authorization.MayClaim
import uy.ct.shortener.shortlink.internal.authorization.MayCreate
import uy.ct.shortener.shortlink.internal.authorization.MayDisable
import uy.ct.shortener.shortlink.internal.authorization.MayList
import uy.ct.shortener.shortlink.internal.authorization.MayRead
import java.net.URI

/**
 * Default [ShortLinkService].
 *
 * - Access rules are the `May...` annotations, enforced by method security. Links are reached
 *   through [ManageableLinks], so another client's are not found.
 * - Generated codes are retried a bounded number of times. Each attempt is one atomic
 *   insert-if-absent, so a taken code costs one more attempt.
 * - Custom codes that would shadow an application route (`api`, `actuator`, `error`) are reserved.
 * - A disabled link keeps its code, so it cannot be registered again.
 * - Redirects go through the [RedirectCache]. Every other read, including the ones that decide who
 *   may see or disable a link, reads the repository, so they never see a stale link.
 */
@Service
class DefaultShortLinkService(
    private val repository: ShortLinkRepository,
    private val codeGenerator: ShortCodeGenerator,
    private val manageableLinks: ManageableLinks,
    private val redirectCache: RedirectCache,
    private val observations: ObservationRegistry,
) : ShortLinkService {

    @MayCreate
    override fun shorten(targetUrl: String, createdBy: String): ShortLink {
        val uri = parseTargetUrl(targetUrl)
        repeat(MAX_GENERATION_ATTEMPTS) {
            val result = insert(codeGenerator.generate(), uri, createdBy)
            if (result is InsertResult.Created) return result.link
        }
        throw ShortCodeExhaustionException(MAX_GENERATION_ATTEMPTS)
    }

    @MayClaim
    override fun claim(shortCode: ShortCode, targetUrl: String, createdBy: String): ShortLink {
        val uri = parseTargetUrl(targetUrl)
        if (shortCode.value in RESERVED_CODES) throw ShortCodeUnavailableException(shortCode)
        return when (val result = insert(shortCode, uri, createdBy)) {
            is InsertResult.Created -> result.link
            InsertResult.Taken -> throw ShortCodeUnavailableException(shortCode)
        }
    }

    override fun resolve(shortCode: ShortCode): ShortLink {
        val link = redirectCache
            .find(shortCode) { observed("shortlink.load") { repository.findByShortCode(it) } }
            .orThrow(shortCode)
        if (link.isDisabled) throw ShortLinkDisabledException(shortCode)
        return link
    }

    @MayRead
    override fun get(shortCode: ShortCode): ShortLink = manageableLinks.get(shortCode)

    @MayList
    override fun list(filter: CreatedByFilter, pageable: Pageable): Slice<ShortLink> = repository.list(filter, pageable)

    @MayDisable
    override fun disable(shortCode: ShortCode, disabledBy: String) {
        manageableLinks.get(shortCode)
        repository.disable(shortCode, disabledBy)
        redirectCache.evict(shortCode)
    }

    private fun insert(shortCode: ShortCode, uri: URI, createdBy: String): InsertResult =
        observed("shortlink.insert") { repository.insertIfAbsent(shortCode, uri, createdBy) }

    private fun <T> observed(name: String, block: () -> T): T =
        Observation.createNotStarted(name, observations).observe(block)

    private fun parseTargetUrl(raw: String): URI =
        try {
            URI.create(raw).also(ShortLink::requireValidTargetUrl)
        } catch (ex: IllegalArgumentException) {
            throw InvalidTargetUrlException(raw, ex)
        }

    companion object {
        private const val MAX_GENERATION_ATTEMPTS = 5

        private val RESERVED_CODES = setOf("api", "actuator", "error")
    }
}
