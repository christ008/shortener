package uy.ct.shortener.shortlink.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.TargetUrlNotAllowedException
import java.net.URI
import kotlin.test.assertFailsWith

/**
 * Which hosts a public instance will shorten links to: nothing is restricted without an allowlist, and with one only
 * its hosts pass, however the URL is written to look like one of them.
 */
class TargetUrlPolicyTest {

    private fun uri(value: String) = URI.create(value)

    private fun allows(policy: TargetUrlPolicy, target: String) = runCatching { policy.require(uri(target)) }.isSuccess

    @Test
    fun `accepts every host when no allowlist is configured`() {
        assertThat(allows(AnyTarget, "https://anything.test/path")).isTrue
        assertThat(TargetUrlProperties().toPolicy()).isSameAs(AnyTarget)
    }

    @Test
    fun `accepts a host that is listed, ignoring case, and only that host`() {
        val policy = AllowedHosts(listOf("Example.com"))

        assertThat(allows(policy, "https://example.com/a")).isTrue
        assertThat(allows(policy, "http://EXAMPLE.COM:8080/a?q=1#f")).isTrue
        assertThat(allows(policy, "https://www.example.com/a")).isFalse
        assertThat(allows(policy, "https://other.test/a")).isFalse
    }

    @Test
    fun `a wildcard entry accepts subdomains but not the domain itself`() {
        val policy = AllowedHosts(listOf("*.example.org"))

        assertThat(allows(policy, "https://docs.example.org/")).isTrue
        assertThat(allows(policy, "https://a.b.example.org/")).isTrue
        assertThat(allows(policy, "https://example.org/")).isFalse
    }

    @Test
    fun `is not fooled by a host that merely contains or ends like an allowed one`() {
        val policy = AllowedHosts(listOf("example.com", "*.example.org"))

        listOf(
            "https://example.com.evil.test/",
            "https://evilexample.com/",
            "https://example.com@evil.test/",
            "https://evil.test/?next=https://example.com",
            "https://evil.test/example.com",
            "https://evilexample.org/",
            "https://example.org.evil.test/",
        ).forEach { assertThat(allows(policy, it)).describedAs(it).isFalse }
    }

    @Test
    fun `names the refused host, and only that`() {
        val failure = assertFailsWith<TargetUrlNotAllowedException> { AllowedHosts(listOf("example.com")).require(uri("https://evil.test/x")) }

        assertThat(failure.body.detail).isEqualTo("Links to 'evil.test' are not accepted by this service")
        assertThat(failure.statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `refuses an allowlist that is empty or has a blank entry, which would accept nothing or everything by mistake`() {
        assertFailsWith<IllegalArgumentException> { AllowedHosts(emptyList()) }
        assertFailsWith<IllegalArgumentException> { AllowedHosts(listOf("example.com", " ")) }
        assertFailsWith<IllegalArgumentException> { AllowedHosts(listOf("*.")) }
    }

    @Test
    fun `settings with hosts become an allowlist`() {
        val policy = TargetUrlProperties(allowedHosts = listOf("example.com")).toPolicy()

        assertThat(policy).isInstanceOf(AllowedHosts::class.java)
        assertThat(allows(policy, "https://example.com/")).isTrue
        assertThat(allows(policy, "https://evil.test/")).isFalse
    }
}
