package uy.ct.shortener.analytics

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * What a `Referer` header becomes: the host it names, normalised so one site counts once, or [Referrer.Direct] when it names none
 * that can be counted. The headers are the kinds browsers and apps send, and the kinds a forger would.
 */
class ReferrerTest {

    private fun of(header: String) = Referrer.of(header)

    @Test
    fun `keeps the host and drops the path, the query, the fragment, a port and user info`() {
        assertThat(of("https://news.ycombinator.com/item?id=41234567&p=2#c")).isEqualTo(Referrer.Host("news.ycombinator.com"))
        assertThat(of("https://t.co/AbCdEf")).isEqualTo(Referrer.Host("t.co"))
        assertThat(of("http://example.com:8080/a")).isEqualTo(Referrer.Host("example.com"))
        assertThat(of("https://user:secret@example.com/a")).isEqualTo(Referrer.Host("example.com"))
        assertThat(of("https://example.com")).isEqualTo(Referrer.Host("example.com"))
        assertThat(of("https://example.com?x=1")).isEqualTo(Referrer.Host("example.com"))
    }

    @Test
    fun `one site counts once, in lowercase, without www and without a trailing dot`() {
        listOf("https://www.Example.com/", "https://EXAMPLE.COM", "https://example.com./x", "https://www.example.com:443").forEach {
            assertThat(of(it)).describedAs(it).isEqualTo(Referrer.Host("example.com"))
        }
        assertThat(of("https://www2.example.com/")).isEqualTo(Referrer.Host("www2.example.com"))
    }

    @Test
    fun `an app that refers names its package`() {
        assertThat(of("android-app://com.slack/")).isEqualTo(Referrer.Host("com.slack"))
        assertThat(of("android-app://com.google.android.gm")).isEqualTo(Referrer.Host("com.google.android.gm"))
    }

    @Test
    fun `an address literal is a host too`() {
        assertThat(of("http://192.0.2.7:8080/")).isEqualTo(Referrer.Host("192.0.2.7"))
        assertThat(of("http://[2001:DB8::1]:8443/x")).isEqualTo(Referrer.Host("[2001:db8::1]"))
    }

    @Test
    fun `no header, or one with no host that can be counted, is direct`() {
        listOf(
            "",
            "   ",
            "about:blank",
            "file:///etc/passwd",
            "example.com/no-scheme",
            "://example.com",
            "https://",
            "https:///path",
            "https://exa mple.com/",
            "https://exa_mple.com/",
            "https://<script>/",
            "https://[2001:db8::1/",
            "https://-example.com/",
            "https://" + "a".repeat(254) + "/",
            "ht tp://example.com/",
        ).forEach { assertThat(of(it)).describedAs("'$it'").isEqualTo(Referrer.Direct) }
    }

    @Test
    fun `a host name may be as long as DNS allows, and no longer`() {
        val longest = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)

        assertThat(longest).hasSize(253)
        assertThat(of("https://$longest/")).isEqualTo(Referrer.Host(longest))
        assertThat(of("https://${longest}e/")).isEqualTo(Referrer.Direct)
    }

    @Test
    fun `only letters, digits, dots and dashes make a host name, and only hex digits, colons and dots an address literal`() {
        listOf("https://exa`mple.com/", "https://exa{mple.com/", "https://.example.com/", "http://[]/", "http://[2001:db8::g]/", "http://[2001:db8::`]/")
            .forEach { assertThat(of(it)).describedAs(it).isEqualTo(Referrer.Direct) }
        assertThat(of("http://[fe80::09af:AF90]/")).isEqualTo(Referrer.Host("[fe80::09af:af90]"))
        assertThat(of("https://a-0.z9.example/")).isEqualTo(Referrer.Host("a-0.z9.example"))
    }

    @Test
    fun `reads only the start of a very long header`() {
        assertThat(of("https://example.com/" + "x".repeat(10_000))).isEqualTo(Referrer.Host("example.com"))
        assertThat(of("x".repeat(3_000) + "https://example.com/")).isEqualTo(Referrer.Direct)
    }

    @Test
    fun `a host is never built from something that is not one`() {
        assertFailsWith<IllegalArgumentException> { Referrer.Host("") }
        assertFailsWith<IllegalArgumentException> { Referrer.Host("Example.com") }
        assertFailsWith<IllegalArgumentException> { Referrer.Host("(direct)") }
        assertFailsWith<IllegalArgumentException> { Referrer.Host("[::1") }
    }
}
