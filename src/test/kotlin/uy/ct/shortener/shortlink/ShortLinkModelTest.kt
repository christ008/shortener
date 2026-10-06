package uy.ct.shortener.shortlink

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Instant
import kotlin.reflect.KClass
import kotlin.test.assertFailsWith

/**
 * The behavior the domain types carry, so callers tell them what to check instead of unpacking them: who created a
 * link, whether it still redirects, which codes are kept, and what a listing covers by default.
 */
class ShortLinkModelTest {

    private val active = ShortLink(ShortCode("aaaaaaa"), URI.create("https://example.com"), Actor.Client("alice"), Instant.EPOCH)

    private val disabled = active.copy(status = LinkStatus.Disabled(Instant.EPOCH, Actor.Client("root")))

    @Test
    fun `a link knows who created it`() {
        assertThat(active.isCreatedBy("alice")).isTrue
        assertThat(active.isCreatedBy("bob")).isFalse
        assertThat(active.copy(createdBy = Actor.Unknown).isCreatedBy("alice")).isFalse
    }

    @Test
    fun `an active link is returned, and a disabled one refuses to be treated as active`() {
        assertThat(active.requireActive()).isSameAs(active)
        assertThat(active.isActive).isTrue
        assertThat(disabled.isActive).isFalse

        val failure = assertFailsWith<ShortLinkDisabledException> { disabled.requireActive() }
        assertThat(failure.statusCode.value()).isEqualTo(410)
    }

    @Test
    fun `the codes that would shadow a route cannot be claimed, and others can`() {
        ShortCode.RESERVED.forEach { assertFailsWith<ShortCodeUnavailableException> { ShortCode(it).requireClaimable() } }

        assertThat(ShortCode("my-promo").requireClaimable()).isEqualTo(ShortCode("my-promo"))
    }

    @Test
    fun `a listing covers the named client's links, else everything for an administrator, else the caller's own`() {
        assertThat(CreatedByFilter.of("bob", "alice", callerIsAdministrator = false)).isEqualTo(CreatedByFilter.Only("bob"))
        assertThat(CreatedByFilter.of("bob", "root", callerIsAdministrator = true)).isEqualTo(CreatedByFilter.Only("bob"))
        assertThat(CreatedByFilter.of(null, "root", callerIsAdministrator = true)).isEqualTo(CreatedByFilter.Anyone)
        assertThat(CreatedByFilter.of(null, "alice", callerIsAdministrator = false)).isEqualTo(CreatedByFilter.Only("alice"))
    }

    @Test
    fun `a filter limited to one client says so only for that client`() {
        assertThat(CreatedByFilter.Only("alice").isLimitedTo("alice")).isTrue
        assertThat(CreatedByFilter.Only("alice").isLimitedTo("bob")).isFalse
        assertThat(CreatedByFilter.Anyone.isLimitedTo("alice")).isFalse
    }

    @Test
    fun `the failures the API reports are exactly the ones this package declares`() {
        val declared: Set<KClass<out ShortLinkException>> = ShortLinkException::class.sealedSubclasses.toSet()

        assertThat(declared.map { it.simpleName }).containsExactlyInAnyOrder(
            "InvalidSortException",
            "InvalidTargetUrlException",
            "ShortCodeExhaustionException",
            "ShortCodeUnavailableException",
            "ShortLinkDisabledException",
            "ShortLinkNotFoundException",
            "StorageUnavailableException",
            "TargetUrlNotAllowedException",
        )
    }
}
