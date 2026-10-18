package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.analytics.Referrer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * How many referrer hosts an instance counts for a link in a day: the first ones, as many as it admits, and every later click from
 * them. Any other host is other, so a forged `Referer` cannot add rows without end.
 */
class ReferrerAdmissionTest {

    private val registry = SimpleMeterRegistry()

    private fun capped() = registry.counter("analytics.referrers.capped").count()

    private fun host(name: String) = Referrer.Host(name)

    @Test
    fun `admits the first hosts of a link and day, and keeps admitting them`() {
        val admission = ReferrerAdmission(2, 100, registry)

        assertThat(admission.admit("abc", 1, host("a.example"))).isEqualTo(host("a.example"))
        assertThat(admission.admit("abc", 1, host("b.example"))).isEqualTo(host("b.example"))
        assertThat(admission.admit("abc", 1, host("c.example"))).isEqualTo(Referrer.Other)
        assertThat(admission.admit("abc", 1, host("a.example"))).isEqualTo(host("a.example"))
        assertThat(capped()).isEqualTo(1.0)
    }

    @Test
    fun `each link and each day has a cap of its own`() {
        val admission = ReferrerAdmission(1, 100, registry)

        assertThat(admission.admit("abc", 1, host("a.example"))).isEqualTo(host("a.example"))
        assertThat(admission.admit("abd", 1, host("b.example"))).isEqualTo(host("b.example"))
        assertThat(admission.admit("abc", 2, host("c.example"))).isEqualTo(host("c.example"))
        assertThat(capped()).isZero()
    }

    @Test
    fun `direct and other always pass, and take no place`() {
        val admission = ReferrerAdmission(1, 100, registry)

        repeat(3) {
            assertThat(admission.admit("abc", 1, Referrer.Direct)).isEqualTo(Referrer.Direct)
            assertThat(admission.admit("abc", 1, Referrer.Other)).isEqualTo(Referrer.Other)
        }
        assertThat(admission.admit("abc", 1, host("a.example"))).isEqualTo(host("a.example"))
    }

    @Test
    fun `admits exactly as many hosts as it may when many threads bring new ones at once`() {
        val admission = ReferrerAdmission(50, 100, registry)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        val results = (0 until 8).map { thread ->
            pool.submit<List<Referrer>> {
                start.await()
                (0 until 100).map { admission.admit("abc", 1, host("h$thread-$it.example")) }
            }
        }

        start.countDown()
        val admitted = results.flatMap { it.get(30, TimeUnit.SECONDS) }.filter { it != Referrer.Other }
        pool.shutdown()

        assertThat(admitted).hasSize(50).doesNotHaveDuplicates()
        assertThat(capped()).isEqualTo(750.0)
    }
}
