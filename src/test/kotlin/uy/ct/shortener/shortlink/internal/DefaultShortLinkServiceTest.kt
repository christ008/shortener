package uy.ct.shortener.shortlink.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.InvalidTargetUrlException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeExhaustionException
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortLinkNotFoundException
import java.net.URI
import kotlin.test.assertFailsWith

class DefaultShortLinkServiceTest {

    private val repository = InMemoryShortLinkRepository()

    private fun serviceWith(vararg codes: String): Pair<DefaultShortLinkService, GeneratorProbe> {
        val generator = GeneratorProbe(codes.map(::ShortCode))
        return DefaultShortLinkService(repository, generator) to generator
    }

    /** Hands out the given codes in order, repeating the last one forever, and counts how often it was asked. */
    private class GeneratorProbe(private val codes: List<ShortCode>) : ShortCodeGenerator {
        var calls = 0
            private set

        override fun generate(): ShortCode = codes[minOf(calls++, codes.lastIndex)]
    }

    @Test
    fun `shortens a url using the generated code and persists it`() {
        val (service, _) = serviceWith("aaaaaaa")

        val link = service.shorten("https://example.com/some/path")

        assertThat(link.shortCode).isEqualTo(ShortCode("aaaaaaa"))
        assertThat(link.targetUrl).isEqualTo(URI.create("https://example.com/some/path"))
        assertThat(repository.saved).containsExactly(link)
    }

    @Test
    fun `retries with a fresh code when the first candidate is already taken`() {
        repository.seed(ShortCode("takenAA"))
        val (service, generator) = serviceWith("takenAA", "freeBBB")

        val link = service.shorten("https://example.com")

        assertThat(link.shortCode).isEqualTo(ShortCode("freeBBB"))
        assertThat(generator.calls).isEqualTo(2)
    }

    @Test
    fun `gives up with an exhaustion error after five taken candidates and persists nothing new`() {
        repository.seed(ShortCode("takenAA"))
        val (service, generator) = serviceWith("takenAA")

        assertFailsWith<ShortCodeExhaustionException> { service.shorten("https://example.com") }

        assertThat(generator.calls).isEqualTo(5)
        assertThat(repository.saved).hasSize(1)
    }

    @Test
    fun `rejects unparseable and non-http urls before generating a code`() {
        val (service, generator) = serviceWith("aaaaaaa")

        listOf("not a url", "/relative/path", "ftp://example.com/file", "mailto:someone@example.com").forEach {
            assertFailsWith<InvalidTargetUrlException>("expected '$it' to be rejected") { service.shorten(it) }
        }

        assertThat(generator.calls).isZero()
        assertThat(repository.saved).isEmpty()
    }

    @Test
    fun `resolves an existing short code`() {
        val (service, _) = serviceWith("aaaaaaa")
        val created = service.shorten("https://example.com")

        assertThat(service.resolve(ShortCode("aaaaaaa"))).isSameAs(created)
    }

    @Test
    fun `fails to resolve an unknown short code`() {
        val (service, _) = serviceWith("aaaaaaa")

        assertFailsWith<ShortLinkNotFoundException> { service.resolve(ShortCode("zzzzzzz")) }
    }
}
