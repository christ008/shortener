package uy.ct.shortener.shortlink.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.InvalidTargetUrlException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeExhaustionException
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortCodeUnavailableException
import uy.ct.shortener.shortlink.ShortLinkNotFoundException
import java.net.URI
import kotlin.test.assertFailsWith

/**
 * Unit tests for [DefaultShortLinkService] using [InMemoryShortLinkRepository] and a scripted
 * generator that counts how many codes were requested.
 */
class DefaultShortLinkServiceTest {

    private companion object {
        const val CLIENT = "test-client"
    }

    private val repository = InMemoryShortLinkRepository()

    private fun serviceWith(vararg codes: String): Pair<DefaultShortLinkService, GeneratorProbe> {
        val generator = GeneratorProbe(codes.map(::ShortCode))
        return DefaultShortLinkService(repository, generator) to generator
    }

    private class GeneratorProbe(private val codes: List<ShortCode>) : ShortCodeGenerator {
        var calls = 0
            private set

        override fun generate(): ShortCode = codes[minOf(calls++, codes.lastIndex)]
    }

    @Test
    fun `shortens a url using the generated code and persists it`() {
        val (service, _) = serviceWith("aaaaaaa")

        val link = service.shorten("https://example.com/some/path", CLIENT)

        assertThat(link.shortCode).isEqualTo(ShortCode("aaaaaaa"))
        assertThat(link.targetUrl).isEqualTo(URI.create("https://example.com/some/path"))
        assertThat(link.createdBy).isEqualTo(CLIENT)
        assertThat(repository.saved).containsExactly(link)
    }

    @Test
    fun `retries with a fresh code when the first candidate is already taken`() {
        repository.seed(ShortCode("takenAA"))
        val (service, generator) = serviceWith("takenAA", "freeBBB")

        val link = service.shorten("https://example.com", CLIENT)

        assertThat(link.shortCode).isEqualTo(ShortCode("freeBBB"))
        assertThat(generator.calls).isEqualTo(2)
    }

    @Test
    fun `gives up with an exhaustion error after five taken candidates and persists nothing new`() {
        repository.seed(ShortCode("takenAA"))
        val (service, generator) = serviceWith("takenAA")

        assertFailsWith<ShortCodeExhaustionException> { service.shorten("https://example.com", CLIENT) }

        assertThat(generator.calls).isEqualTo(5)
        assertThat(repository.saved).hasSize(1)
    }

    @Test
    fun `claims a custom code without generating one`() {
        val (service, generator) = serviceWith("aaaaaaa")

        val link = service.claim(ShortCode("my-promo"), "https://example.com/promo", CLIENT)

        assertThat(link.shortCode).isEqualTo(ShortCode("my-promo"))
        assertThat(generator.calls).isZero()
        assertThat(repository.saved).containsExactly(link)
    }

    @Test
    fun `refuses a custom code that is already taken`() {
        repository.seed(ShortCode("my-promo"))
        val (service, _) = serviceWith("aaaaaaa")

        assertFailsWith<ShortCodeUnavailableException> { service.claim(ShortCode("my-promo"), "https://example.com", CLIENT) }

        assertThat(repository.saved).hasSize(1)
    }

    @Test
    fun `refuses custom codes that would shadow application routes`() {
        val (service, _) = serviceWith("aaaaaaa")

        listOf("api", "actuator", "error").forEach {
            assertFailsWith<ShortCodeUnavailableException>("expected '$it' to be reserved") {
                service.claim(ShortCode(it), "https://example.com", CLIENT)
            }
        }

        assertThat(repository.saved).isEmpty()
    }

    @Test
    fun `rejects an invalid url when claiming a custom code`() {
        val (service, _) = serviceWith("aaaaaaa")

        assertFailsWith<InvalidTargetUrlException> { service.claim(ShortCode("my-promo"), "ftp://example.com", CLIENT) }

        assertThat(repository.saved).isEmpty()
    }

    @Test
    fun `rejects unparseable and non-http urls before generating a code`() {
        val (service, generator) = serviceWith("aaaaaaa")

        listOf("not a url", "/relative/path", "ftp://example.com/file", "mailto:someone@example.com").forEach {
            assertFailsWith<InvalidTargetUrlException>("expected '$it' to be rejected") { service.shorten(it, CLIENT) }
        }

        assertThat(generator.calls).isZero()
        assertThat(repository.saved).isEmpty()
    }

    @Test
    fun `resolves an existing short code`() {
        val (service, _) = serviceWith("aaaaaaa")
        val created = service.shorten("https://example.com", CLIENT)

        assertThat(service.resolve(ShortCode("aaaaaaa"))).isSameAs(created)
    }

    @Test
    fun `fails to resolve an unknown short code`() {
        val (service, _) = serviceWith("aaaaaaa")

        assertFailsWith<ShortLinkNotFoundException> { service.resolve(ShortCode("zzzzzzz")) }
    }
}
