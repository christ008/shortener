package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import uy.ct.shortener.LogCapture
import uy.ct.shortener.shortlink.LinkFollowed
import uy.ct.shortener.shortlink.ShortCode

/**
 * What the listener does with a followed link: classifies the user agent and records the click, and never lets a failure out,
 * because it runs in the thread of a redirect.
 */
class ClickListenerTest {

    private val registry = SimpleMeterRegistry()

    private val recorded = mutableListOf<Pair<ShortCode, DeviceClass>>()

    private fun listener(recorder: ClickRecorder) = ClickListener(recorder, registry, LoggerFactory.getLogger(ClickListener::class.java))

    @Test
    fun `records the click with the class of its user agent`() {
        val listener = listener(object : ClickRecorder {
            override fun record(shortCode: ShortCode, device: DeviceClass) {
                recorded += shortCode to device
            }
        })

        listener.on(LinkFollowed(ShortCode("abc"), "Mozilla/5.0 (iPhone)"))
        listener.on(LinkFollowed(ShortCode("abc"), ""))

        assertThat(recorded).containsExactly(ShortCode("abc") to DeviceClass.MOBILE, ShortCode("abc") to DeviceClass.BOT)
    }

    @Test
    fun `a failure to count is not the redirect's, is counted and is logged once`() {
        LogCapture(ClickListener::class.java.name).use { log ->
            val listener = listener(object : ClickRecorder {
                override fun record(shortCode: ShortCode, device: DeviceClass) = throw IllegalStateException("secret detail")
            })

            repeat(3) { listener.on(LinkFollowed(ShortCode("abc"), "x")) }

            assertThat(registry.counter("analytics.clicks.dropped", "reason", "error").count()).isEqualTo(3.0)
            assertThat(log.events).hasSize(1)
            assertThat(log.events.single().message).contains("IllegalStateException").doesNotContain("secret detail")
        }
    }
}
