package uy.ct.shortener.security.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

/**
 * A nonce is checked by whichever instance gets the request, so what matters is that the ones an instance made are accepted by
 * another with the same secret for exactly as long as promised, and that nothing else is: not an old one, a future one, one made
 * with another secret, or text that is not a nonce.
 */
class DpopNoncesTest {

    private val now = AtomicReference(Instant.parse("2026-10-06T12:00:00Z"))
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId) = this

        override fun instant(): Instant = now.get()
    }
    private val interval = Duration.ofMinutes(5)
    private val secret = "a-secret-shared-by-every-instance".toByteArray()

    private fun nonces(secret: ByteArray = this.secret) = HmacDpopNonces(secret, interval, clock)

    @Test
    fun `a nonce is accepted by another instance with the same secret`() {
        assertThat(nonces().accepts(nonces().current())).isTrue()
    }

    @Test
    fun `the nonce stays the same within an interval and changes with the next`() {
        val first = nonces().current()
        now.updateAndGet { it.plusSeconds(60) }
        assertThat(nonces().current()).isEqualTo(first)

        now.updateAndGet { it.plus(interval) }
        assertThat(nonces().current()).isNotEqualTo(first)
    }

    @Test
    fun `a nonce is good for its interval and the next, and then no more`() {
        val issued = nonces().current()

        now.updateAndGet { it.plus(interval) }
        assertThat(nonces().accepts(issued)).describedAs("one interval later").isTrue()

        now.updateAndGet { it.plus(interval) }
        assertThat(nonces().accepts(issued)).describedAs("two intervals later").isFalse()
    }

    @Test
    fun `a nonce from the future is refused`() {
        val later = Instant.parse("2026-10-06T12:10:00Z")
        val future = HmacDpopNonces(secret, interval, Clock.fixed(later, ZoneOffset.UTC)).current()

        assertThat(nonces().accepts(future)).isFalse()
    }

    @Test
    fun `a nonce made with another secret is refused`() {
        assertThat(nonces("another-secret".toByteArray()).accepts(nonces().current())).isFalse()
    }

    @Test
    fun `text that is not a nonce is refused`() {
        val real = nonces().current()

        listOf(null, "", " ", "not base64 !", "AAAA", real.dropLast(2), real + "AA", real.replaceFirst(real[0], if (real[0] == 'A') 'B' else 'A'))
            .forEach { assertThat(nonces().accepts(it)).describedAs("accepted: $it").isFalse() }
    }

    @Test
    fun `a nonce whose hash is altered is refused even when its interval is current`() {
        val real = nonces().current()
        val last = real.last()

        assertThat(nonces().accepts(real.dropLast(1) + (if (last == 'A') 'B' else 'A'))).isFalse()
    }

    @Test
    fun `an empty secret and an interval that is not positive are refused`() {
        assertThatThrownBy { HmacDpopNonces(ByteArray(0), interval) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { HmacDpopNonces(secret, Duration.ZERO) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `nonces turned off hand none out and accept any proof`() {
        val off = DpopNonces.from(SecurityProperties.Nonce(enabled = false))

        assertThat(off).isSameAs(NoDpopNonces)
        assertThat(off.current()).isNull()
        assertThat(off.accepts(null)).isTrue()
        assertThat(off.accepts("anything")).isTrue()
    }

    @Test
    fun `without a secret each process makes its own, so two do not accept each other's`() {
        val one = DpopNonces.from(SecurityProperties.Nonce())
        val other = DpopNonces.from(SecurityProperties.Nonce())

        assertThat(one.accepts(one.current())).isTrue()
        assertThat(other.accepts(one.current())).isFalse()
    }

    @Test
    fun `with a secret two processes agree`() {
        val settings = SecurityProperties.Nonce(secret = "shared")

        assertThat(DpopNonces.from(settings).accepts(DpopNonces.from(settings).current())).isTrue()
    }
}
