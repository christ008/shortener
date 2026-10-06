package uy.ct.shortener.shortlink.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistry
import io.micrometer.observation.tck.TestObservationRegistryAssert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.InvalidTargetUrlException
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeExhaustionException
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortCodeUnavailableException
import uy.ct.shortener.shortlink.ShortLinkDisabledException
import uy.ct.shortener.shortlink.ShortLinkNotFoundException
import uy.ct.shortener.shortlink.TargetUrlNotAllowedException
import uy.ct.shortener.shortlink.internal.authorization.ManageableLinks
import java.net.URI
import java.time.Duration
import kotlin.test.assertFailsWith

/**
 * Unit tests of how [DefaultShortLinkService] allocates codes and resolves links, using
 * [InMemoryShortLinkRepository] and a scripted generator that counts how many codes were
 * requested. Method security does not apply to an object built directly, so who may do what is
 * covered separately, by `ShortLinkAuthorizationTest`.
 */
class DefaultShortLinkServiceTest {

    private val repository = InMemoryShortLinkRepository()

    private val ticker = FakeTicker()

    private val ttl = Duration.ofSeconds(30)

    private fun serviceWith(
        vararg codes: String,
        observations: ObservationRegistry = ObservationRegistry.NOOP,
        policy: TargetUrlPolicy = AnyTarget,
        audit: AuditTrail = NoAuditTrail,
    ): Pair<DefaultShortLinkService, GeneratorProbe> {
        val generator = GeneratorProbe(codes.map(::ShortCode))
        val cache = CaffeineRedirectCache(RedirectCacheProperties(ttl = ttl), SimpleMeterRegistry(), ticker, Runnable::run)
        return DefaultShortLinkService(repository, generator, ManageableLinks(repository), cache, policy, observations, audit) to generator
    }

    private class GeneratorProbe(private val codes: List<ShortCode>) : ShortCodeGenerator {
        var calls = 0
            private set

        override fun generate(): ShortCode = codes[minOf(calls++, codes.lastIndex)]
    }

    @Test
    fun `shortens a url using the generated code, owned by the given client`() {
        val (service, _) = serviceWith("aaaaaaa")

        val link = service.shorten("https://example.com/some/path", "owner")

        assertThat(link.shortCode).isEqualTo(ShortCode("aaaaaaa"))
        assertThat(link.targetUrl).isEqualTo(URI.create("https://example.com/some/path"))
        assertThat(link.createdBy).isEqualTo(Actor.Client("owner"))
        assertThat(repository.saved).containsExactly(link)
    }

    @Test
    fun `retries with a fresh code when the first candidate is already taken`() {
        repository.seed(ShortCode("takenAA"))
        val (service, generator) = serviceWith("takenAA", "freeBBB")

        val link = service.shorten("https://example.com", "owner")

        assertThat(link.shortCode).isEqualTo(ShortCode("freeBBB"))
        assertThat(generator.calls).isEqualTo(2)
    }

    @Test
    fun `gives up with an exhaustion error after five taken candidates and persists nothing new`() {
        repository.seed(ShortCode("takenAA"))
        val (service, generator) = serviceWith("takenAA")

        assertFailsWith<ShortCodeExhaustionException> { service.shorten("https://example.com", "owner") }

        assertThat(generator.calls).isEqualTo(5)
        assertThat(repository.saved).hasSize(1)
    }

    @Test
    fun `claims a custom code without generating one`() {
        val (service, generator) = serviceWith("aaaaaaa")

        val link = service.claim(ShortCode("my-promo"), "https://example.com/promo", "owner")

        assertThat(link.shortCode).isEqualTo(ShortCode("my-promo"))
        assertThat(generator.calls).isZero()
        assertThat(repository.saved).containsExactly(link)
    }

    @Test
    fun `refuses a custom code that is already taken`() {
        repository.seed(ShortCode("my-promo"))
        val (service, _) = serviceWith("aaaaaaa")

        assertFailsWith<ShortCodeUnavailableException> { service.claim(ShortCode("my-promo"), "https://example.com", "owner") }

        assertThat(repository.saved).hasSize(1)
    }

    @Test
    fun `refuses custom codes that would shadow application routes`() {
        val (service, _) = serviceWith("aaaaaaa")

        listOf("api", "actuator", "error").forEach {
            assertFailsWith<ShortCodeUnavailableException>("expected '$it' to be reserved") {
                service.claim(ShortCode(it), "https://example.com", "owner")
            }
        }

        assertThat(repository.saved).isEmpty()
    }

    @Test
    fun `rejects invalid urls before generating a code or storing anything`() {
        val (service, generator) = serviceWith("aaaaaaa")

        listOf("not a url", "/relative/path", "ftp://example.com/file", "mailto:someone@example.com").forEach {
            assertFailsWith<InvalidTargetUrlException>("expected '$it' to be rejected") { service.shorten(it, "owner") }
            assertFailsWith<InvalidTargetUrlException> { service.claim(ShortCode("my-promo"), it, "owner") }
        }

        assertThat(generator.calls).isZero()
        assertThat(repository.saved).isEmpty()
    }

    @Test
    fun `resolves an existing short code, but not a disabled one`() {
        val (service, _) = serviceWith("aaaaaaa")
        service.shorten("https://example.com/target", "owner")

        assertThat(service.resolve(ShortCode("aaaaaaa")).targetUrl).isEqualTo(URI.create("https://example.com/target"))

        service.disable(ShortCode("aaaaaaa"), "owner")

        assertFailsWith<ShortLinkDisabledException> { service.resolve(ShortCode("aaaaaaa")) }
    }

    @Test
    fun `fails to resolve an unknown short code`() {
        val (service, _) = serviceWith("aaaaaaa")

        assertFailsWith<ShortLinkNotFoundException> { service.resolve(ShortCode("zzzzzzz")) }
    }

    @Test
    fun `a disabled link keeps its code taken, so nobody else can register it`() {
        val (service, _) = serviceWith("aaaaaaa")
        service.claim(ShortCode("my-promo"), "https://example.com", "owner")
        repository.disable(ShortCode("my-promo"), "owner")

        assertFailsWith<ShortCodeUnavailableException> { service.claim(ShortCode("my-promo"), "https://evil.example.com", "owner") }
    }

    @Test
    fun `serves repeated redirects from the cache after reading the link once`() {
        val (service, _) = serviceWith("aaaaaaa")
        service.shorten("https://example.com/target", "owner")
        val before = repository.lookups

        repeat(5) { assertThat(service.resolve(ShortCode("aaaaaaa")).targetUrl).isEqualTo(URI.create("https://example.com/target")) }

        assertThat(repository.lookups - before).isEqualTo(1)
    }

    @Test
    fun `does not cache an unknown code, so a link created later works at once`() {
        val (service, _) = serviceWith("aaaaaaa")
        val before = repository.lookups

        repeat(2) { assertFailsWith<ShortLinkNotFoundException> { service.resolve(ShortCode("aaaaaaa")) } }
        service.shorten("https://example.com/target", "owner")

        assertThat(repository.lookups - before).isEqualTo(2)
        assertThat(service.resolve(ShortCode("aaaaaaa")).targetUrl).isEqualTo(URI.create("https://example.com/target"))
    }

    @Test
    fun `does not cache a disabled link`() {
        val (service, _) = serviceWith("aaaaaaa")
        service.shorten("https://example.com/target", "owner")
        repository.disable(ShortCode("aaaaaaa"), "owner")
        val before = repository.lookups

        repeat(2) { assertFailsWith<ShortLinkDisabledException> { service.resolve(ShortCode("aaaaaaa")) } }

        assertThat(repository.lookups - before).isEqualTo(2)
    }

    @Test
    fun `stops serving a link on this instance the moment it is disabled through the service`() {
        val (service, _) = serviceWith("aaaaaaa")
        service.shorten("https://example.com/target", "owner")
        service.resolve(ShortCode("aaaaaaa"))

        service.disable(ShortCode("aaaaaaa"), "owner")

        assertFailsWith<ShortLinkDisabledException> { service.resolve(ShortCode("aaaaaaa")) }
    }

    @Test
    fun `serves a link disabled elsewhere until its entry expires, then stops`() {
        val (service, _) = serviceWith("aaaaaaa")
        service.shorten("https://example.com/target", "owner")
        service.resolve(ShortCode("aaaaaaa"))

        repository.disable(ShortCode("aaaaaaa"), "owner")
        ticker.advance(ttl.minusSeconds(1))
        assertThat(service.resolve(ShortCode("aaaaaaa")).isDisabled).isFalse
        ticker.advance(Duration.ofSeconds(2))

        assertFailsWith<ShortLinkDisabledException> { service.resolve(ShortCode("aaaaaaa")) }
    }

    @Test
    fun `reading a link by its owner is never served from the cache`() {
        val (service, _) = serviceWith("aaaaaaa")
        service.shorten("https://example.com/target", "owner")
        service.resolve(ShortCode("aaaaaaa"))
        repository.disable(ShortCode("aaaaaaa"), "owner")

        assertThat(manageable(service).isDisabled).isTrue
    }

    private fun manageable(service: DefaultShortLinkService) = ManageableLinks(repository).get(ShortCode("aaaaaaa"))

    @Test
    fun `observes the database read of a cache miss but not of a hit`() {
        val observations = TestObservationRegistry.create()
        val (service, _) = serviceWith("aaaaaaa", observations = observations)
        service.shorten("https://example.com/target", "owner")

        repeat(3) { service.resolve(ShortCode("aaaaaaa")) }

        TestObservationRegistryAssert.assertThat(observations).hasNumberOfObservationsWithNameEqualTo("shortlink.load", 1)
    }

    @Test
    fun `observes each attempt to insert a link`() {
        repository.seed(ShortCode("takenAA"))
        val observations = TestObservationRegistry.create()
        val (service, _) = serviceWith("takenAA", "freeBBB", observations = observations)

        service.shorten("https://example.com", "owner")

        TestObservationRegistryAssert.assertThat(observations).hasNumberOfObservationsWithNameEqualTo("shortlink.insert", 2)
    }

    @Test
    fun `refuses a target whose host the policy does not accept, before anything is stored`() {
        val (service, generator) = serviceWith("aaaaaaa", policy = AllowedHosts(listOf("example.com")))

        val failure = assertFailsWith<TargetUrlNotAllowedException> { service.shorten("https://evil.test/phish", "owner") }
        assertFailsWith<TargetUrlNotAllowedException> { service.claim(ShortCode("my-promo"), "https://evil.test/", "owner") }

        assertThat(failure.body.detail).contains("evil.test").doesNotContain("example.com")
        assertThat(generator.calls).isZero()
        assertThat(repository.saved).isEmpty()
    }

    @Test
    fun `accepts a target whose host the policy accepts`() {
        val (service, _) = serviceWith("aaaaaaa", policy = AllowedHosts(listOf("example.com", "*.example.org")))

        assertThat(service.shorten("https://example.com/a", "owner").shortCode).isEqualTo(ShortCode("aaaaaaa"))
        assertThat(service.claim(ShortCode("sub-link"), "https://docs.example.org/b", "owner").shortCode).isEqualTo(ShortCode("sub-link"))
    }

    @Test
    fun `keeps the reserved codes for the application`() {
        val (service, _) = serviceWith("aaaaaaa")

        ShortCode.RESERVED.forEach { reserved ->
            assertFailsWith<ShortCodeUnavailableException>("expected '$reserved' to be reserved") {
                service.claim(ShortCode(reserved), "https://example.com", "owner")
            }
        }
        assertThat(repository.saved).isEmpty()
    }

    private class RecordingAuditTrail : AuditTrail {
        val calls = mutableListOf<String>()

        override fun created(link: uy.ct.shortener.shortlink.ShortLink, custom: Boolean) {
            calls += "created ${link.shortCode} custom=$custom"
        }

        override fun disabled(link: uy.ct.shortener.shortlink.ShortLink, by: String) {
            calls += "disabled ${link.shortCode} by=$by"
        }

        override fun listed(filter: uy.ct.shortener.shortlink.CreatedByFilter) {
            calls += "listed $filter"
        }
    }

    @Test
    fun `audits a generated create, a custom create, a disable and a listing, and a refused create not at all`() {
        val audit = RecordingAuditTrail()
        val (service, _) = serviceWith("aaaaaaa", audit = audit, policy = AllowedHosts(listOf("example.com")))

        service.shorten("https://example.com/a", "owner")
        service.claim(ShortCode("custom1"), "https://example.com/b", "owner")
        assertFailsWith<TargetUrlNotAllowedException> { service.shorten("https://evil.test/", "owner") }
        service.disable(ShortCode("custom1"), "owner")
        service.list(uy.ct.shortener.shortlink.CreatedByFilter.Anyone, org.springframework.data.domain.PageRequest.of(0, 10))

        assertThat(audit.calls).containsExactly(
            "created aaaaaaa custom=false",
            "created custom1 custom=true",
            "disabled custom1 by=owner",
            "listed Anyone",
        )
    }
}
