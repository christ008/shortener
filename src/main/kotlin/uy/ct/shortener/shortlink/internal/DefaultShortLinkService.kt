package uy.ct.shortener.shortlink.internal

import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Slice
import org.springframework.stereotype.Service
import uy.ct.shortener.shortlink.internal.authorization.ManageableLinks
import uy.ct.shortener.shortlink.internal.authorization.MayClaim
import uy.ct.shortener.shortlink.internal.authorization.MayCreate
import uy.ct.shortener.shortlink.internal.authorization.MayDisable
import uy.ct.shortener.shortlink.internal.authorization.MayList
import uy.ct.shortener.shortlink.internal.authorization.MayRead
import uy.ct.shortener.shortlink.InvalidTargetUrlException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeExhaustionException
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortCodeUnavailableException
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkDisabledException
import uy.ct.shortener.shortlink.ShortLinkNotFoundException
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.ShortLinkService
import java.net.URI

/**
 * Default [ShortLinkService]. Who may call each operation is declared by the `May...` annotations,
 * which Spring method security enforces, and links are reached through [ManageableLinks] so
 * another client's are not found. Allocates random codes with bounded retries: each attempt is one
 * atomic insert-if-absent, so the repository settles concurrent requests and a taken code simply
 * costs another attempt. Custom codes that would shadow an application route (`api`, `actuator`,
 * `error`) are reserved. A disabled link keeps its code, so it cannot be registered again.
 * Redirects go through the [RedirectCache]; every other read, including the ones that decide who
 * may see or disable a link, reads the repository so they never see a stale link.
 */
@Service
class DefaultShortLinkService(
    private val repository: ShortLinkRepository,
    private val codeGenerator: ShortCodeGenerator,
    private val manageableLinks: ManageableLinks,
    private val redirectCache: RedirectCache,
) : ShortLinkService {

    @MayCreate
    override fun shorten(targetUrl: String, createdBy: String): ShortLink {
        val uri = parseTargetUrl(targetUrl)
        repeat(MAX_GENERATION_ATTEMPTS) {
            repository.insertIfAbsent(codeGenerator.generate(), uri, createdBy)?.let { return it }
        }
        throw ShortCodeExhaustionException(MAX_GENERATION_ATTEMPTS)
    }

    @MayClaim
    override fun claim(shortCode: ShortCode, targetUrl: String, createdBy: String): ShortLink {
        val uri = parseTargetUrl(targetUrl)
        if (shortCode.value in RESERVED_CODES) throw ShortCodeUnavailableException(shortCode)
        return repository.insertIfAbsent(shortCode, uri, createdBy) ?: throw ShortCodeUnavailableException(shortCode)
    }

    override fun resolve(shortCode: ShortCode): ShortLink {
        var loaded = false
        var found: ShortLink? = null
        redirectCache.find(shortCode) {
            loaded = true
            found = repository.findByShortCode(it)
            found?.takeUnless { link -> link.isDisabled }
        }?.let { return it }
        val link = (if (loaded) found else repository.findByShortCode(shortCode)) ?: throw ShortLinkNotFoundException(shortCode)
        if (link.isDisabled) throw ShortLinkDisabledException(shortCode)
        return link
    }

    @MayRead
    override fun get(shortCode: ShortCode): ShortLink =
        manageableLinks.find(shortCode) ?: throw ShortLinkNotFoundException(shortCode)

    @MayList
    override fun list(createdBy: String?, pageable: Pageable): Slice<ShortLink> = repository.list(createdBy, pageable)

    @MayDisable
    override fun disable(shortCode: ShortCode, disabledBy: String) {
        manageableLinks.find(shortCode) ?: throw ShortLinkNotFoundException(shortCode)
        repository.disable(shortCode, disabledBy)
        redirectCache.evict(shortCode)
    }

    private fun parseTargetUrl(raw: String): URI {
        val uri = try {
            URI.create(raw)
        } catch (ex: IllegalArgumentException) {
            throw InvalidTargetUrlException(raw, ex)
        }
        try {
            ShortLink.requireValidTargetUrl(uri)
        } catch (ex: IllegalArgumentException) {
            throw InvalidTargetUrlException(raw, ex)
        }
        return uri
    }

    companion object {
        private const val MAX_GENERATION_ATTEMPTS = 5

        private val RESERVED_CODES = setOf("api", "actuator", "error")
    }
}
