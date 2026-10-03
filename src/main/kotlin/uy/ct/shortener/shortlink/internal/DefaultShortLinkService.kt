package uy.ct.shortener.shortlink.internal

import org.springframework.stereotype.Service
import uy.ct.shortener.shortlink.InvalidTargetUrlException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeExhaustionException
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortCodeUnavailableException
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkNotFoundException
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.ShortLinkService
import java.net.URI

/**
 * Default [ShortLinkService]. Allocates random codes with bounded retries: each attempt is one
 * atomic insert-if-absent, so the repository settles concurrent requests and a taken code simply
 * costs another attempt. Custom codes that would shadow an application route (`api`, `actuator`,
 * `error`) are reserved.
 */
@Service
class DefaultShortLinkService(
    private val repository: ShortLinkRepository,
    private val codeGenerator: ShortCodeGenerator,
) : ShortLinkService {

    override fun shorten(targetUrl: String, createdBy: String): ShortLink {
        val uri = parseTargetUrl(targetUrl)
        repeat(MAX_GENERATION_ATTEMPTS) {
            repository.insertIfAbsent(codeGenerator.generate(), uri, createdBy)?.let { return it }
        }
        throw ShortCodeExhaustionException(MAX_GENERATION_ATTEMPTS)
    }

    override fun claim(shortCode: ShortCode, targetUrl: String, createdBy: String): ShortLink {
        val uri = parseTargetUrl(targetUrl)
        if (shortCode.value in RESERVED_CODES) throw ShortCodeUnavailableException(shortCode)
        return repository.insertIfAbsent(shortCode, uri, createdBy) ?: throw ShortCodeUnavailableException(shortCode)
    }

    override fun resolve(shortCode: ShortCode): ShortLink =
        repository.findByShortCode(shortCode) ?: throw ShortLinkNotFoundException(shortCode)

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
