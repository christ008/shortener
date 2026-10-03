package uy.ct.shortener.shortlink.internal

import org.mockito.Mockito
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkRepository

/** Fakes only the three methods the service uses; everything else on [ShortLinkRepository] is delegated to an unused mock. */
class InMemoryShortLinkRepository(
    delegate: ShortLinkRepository = Mockito.mock(ShortLinkRepository::class.java),
) : ShortLinkRepository by delegate {

    val saved = mutableListOf<ShortLink>()

    fun seed(shortCode: ShortCode) {
        saved += ShortLink(shortCode, java.net.URI.create("https://seed.example.com"))
    }

    override fun existsByShortCode(shortCode: ShortCode): Boolean = saved.any { it.shortCode == shortCode }

    override fun findByShortCode(shortCode: ShortCode): ShortLink? = saved.find { it.shortCode == shortCode }

    override fun <S : ShortLink> save(entity: S): S {
        saved += entity
        return entity
    }
}
