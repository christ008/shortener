package uy.ct.shortener.shortlink.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortCodeGenerator
import uy.ct.shortener.shortlink.ShortLinkRepository
import uy.ct.shortener.shortlink.internal.authorization.ManageableLinks
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrent requests that draw the same free code must all succeed with distinct codes,
 * because the database lets exactly one insert win. The generator holds every thread at a
 * barrier until all of them have drawn the contested code, so they all insert it at once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ShortCodeRaceIntegrationTest {

    @Autowired
    lateinit var repository: ShortLinkRepository

    @Test
    fun `concurrent requests that pick the same free code all succeed with distinct codes`() {
        val threads = 8
        val contested = ShortCode("raceAAA")
        val barrier = CyclicBarrier(threads)
        val calls = AtomicInteger()
        val generator = ShortCodeGenerator {
            val n = calls.incrementAndGet()
            if (n <= threads) {
                barrier.await(10, TimeUnit.SECONDS)
                contested
            } else {
                ShortCode("retry%02d".format(n))
            }
        }
        val service = DefaultShortLinkService(repository, generator, ManageableLinks(repository), RedirectCache(RedirectCacheProperties(), SimpleMeterRegistry()))

        val pool = Executors.newFixedThreadPool(threads)
        val results = try {
            List(threads) { i -> pool.submit<ShortCode> { service.shorten("https://example.com/$i", "race-test").shortCode } }
                .map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertThat(results).doesNotHaveDuplicates()
        assertThat(results.count { it == contested }).isEqualTo(1)
        assertThat(results.all { repository.findByShortCode(it) != null }).isTrue
    }
}
