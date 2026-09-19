package uy.ct.shortener.shortlink.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uy.ct.shortener.shortlink.InvalidTargetUrlException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeExhaustionException
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkNotFoundException
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.ShortLinkService
import java.net.URI

@Service
class DefaultShortLinkService(
    private val repository: ShortLinkRepository,
    private val codeGenerator: ShortCodeGenerator,
) : ShortLinkService {

    @Transactional
    override fun shorten(targetUrl: String): ShortLink {
        val uri = parseTargetUrl(targetUrl)
        val shortCode = allocateShortCode()
        return repository.save(ShortLink(shortCode = shortCode, targetUrl = uri))
    }

    @Transactional(readOnly = true)
    override fun resolve(shortCode: ShortCode): ShortLink =
        repository.findByShortCode(shortCode) ?: throw ShortLinkNotFoundException(shortCode)

    private fun allocateShortCode(): ShortCode {
        repeat(MAX_GENERATION_ATTEMPTS) {
            val candidate = codeGenerator.generate()
            if (!repository.existsByShortCode(candidate)) {
                return candidate
            }
        }
        throw ShortCodeExhaustionException(MAX_GENERATION_ATTEMPTS)
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
        /** Generous relative to the 62^7 code space - this is a backstop, not an expected path. */
        private const val MAX_GENERATION_ATTEMPTS = 5
    }
}
