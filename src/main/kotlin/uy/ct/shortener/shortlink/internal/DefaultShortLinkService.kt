package uy.ct.shortener.shortlink.internal

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.CreatedByFilter
import uy.ct.shortener.shortlink.InsertResult
import uy.ct.shortener.shortlink.InvalidTargetUrlException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeExhaustionException
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortCodeUnavailableException
import uy.ct.shortener.shortlink.ShortLink
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
 * - Access rules are the `May...` annotations, enforced by method security. Links are reached through [ManageableLinks].
 * - Generated codes are retried a bounded number of times, each attempt one atomic insert-if-absent.
 * - Custom codes may not be [ShortCode.RESERVED].
 * - Which hosts a link may point to is the [TargetUrlPolicy]'s decision.
 * - A disabled link keeps its code.
 * - Creates, disables and listings across clients go to the [AuditTrail].
 * - Redirects go through the [RedirectCache]. Every other read uses the repository.
 */
@Service
class DefaultShortLinkService(
    private val repository: ShortLinkRepository,
    private val codeGenerator: ShortCodeGenerator,
    private val manageableLinks: ManageableLinks,
    private val redirectCache: RedirectCache,
    private val targetUrlPolicy: TargetUrlPolicy,
    private val observations: ObservationRegistry,
    private val audit: AuditTrail,
) : ShortLinkService {

    @MayCreate
    override fun shorten(targetUrl: String, createdBy: Actor.Client): ShortLink {
        val uri = parseTargetUrl(targetUrl)
        repeat(MAX_GENERATION_ATTEMPTS) {
            when (val result = insert(codeGenerator.generate(), uri, createdBy)) {
                is InsertResult.Created -> return result.link.also { audit.created(it, custom = false) }
                InsertResult.Taken -> Unit
            }
        }
        throw ShortCodeExhaustionException(MAX_GENERATION_ATTEMPTS)
    }

    @MayClaim
    override fun claim(shortCode: ShortCode, targetUrl: String, createdBy: Actor.Client): ShortLink {
        val uri = parseTargetUrl(targetUrl)
        return when (val result = insert(shortCode.requireClaimable(), uri, createdBy)) {
            is InsertResult.Created -> result.link.also { audit.created(it, custom = true) }
            InsertResult.Taken -> throw ShortCodeUnavailableException(shortCode)
        }
    }

    override fun resolve(shortCode: ShortCode): ShortLink =
        redirectCache
            .find(shortCode) { observed("shortlink.load") { repository.findByShortCode(it) } }
            .orThrow(shortCode)
            .requireActive()

    @MayRead
    override fun get(shortCode: ShortCode): ShortLink = manageableLinks.get(shortCode)

    @MayList
    override fun list(filter: CreatedByFilter, pageable: Pageable): Page<ShortLink> {
        audit.listed(filter)
        return repository.list(filter, pageable)
    }

    @MayDisable
    override fun disable(shortCode: ShortCode, disabledBy: Actor.Client) {
        val link = manageableLinks.get(shortCode)
        repository.disable(shortCode, disabledBy.name)
        redirectCache.evict(shortCode)
        audit.disabled(link, disabledBy)
    }

    private fun insert(shortCode: ShortCode, uri: URI, createdBy: Actor.Client): InsertResult =
        observed("shortlink.insert") { repository.insertIfAbsent(shortCode, uri, createdBy.name) }

    private fun <T> observed(name: String, block: () -> T): T =
        Observation.createNotStarted(name, observations).observe(block)

    private fun parseTargetUrl(raw: String): URI =
        try {
            URI.create(raw).also(ShortLink::requireValidTargetUrl)
        } catch (ex: IllegalArgumentException) {
            throw InvalidTargetUrlException(raw, ex)
        }.let(targetUrlPolicy::require)

    companion object {
        private const val MAX_GENERATION_ATTEMPTS = 5
    }
}
