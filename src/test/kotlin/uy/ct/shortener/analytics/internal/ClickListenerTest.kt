package uy.ct.shortener.analytics.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import uy.ct.shortener.LogCapture
import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer
import uy.ct.shortener.shortlink.LinkFollowed
import uy.ct.shortener.shortlink.ShortCode

/**
 * What the listener does with a followed link: classifies the user agent and records the click, and never lets a failure out,
 * because it runs in the thread of a redirect.
 */
class ClickListenerTest {

    private val registry = SimpleMeterRegistry()

    private val recorded = mutableListOf<Click>()

    private fun listener(recorder: ClickRecorder) = ClickListener(recorder, registry, LoggerFactory.getLogger(ClickListener::class.java))

    @Test
    fun `records the click with the class of its user agent, the host it came from and its language`() {
        val listener = listener(object : ClickRecorder {
            override fun record(click: Click) {
                recorded += click
            }
        })

        listener.on(LinkFollowed(ShortCode("abc"), "Mozilla/5.0 (iPhone)", "https://www.Example.com/page?q=1", "es-UY,es;q=0.9,en;q=0.8"))
        listener.on(LinkFollowed(ShortCode("abc"), "", "", ""))

        assertThat(recorded).containsExactly(
            Click(ShortCode("abc"), DeviceClass.MOBILE, Referrer.Host("example.com"), Language.Code("es")),
            Click(ShortCode("abc"), DeviceClass.BOT, Referrer.Direct, Language.Unknown),
        )
    }

    @Test
    fun `a failure to count is not the redirect's, is counted and is logged once`() {
        LogCapture(ClickListener::class.java.name).use { log ->
            val listener = listener(object : ClickRecorder {
                override fun record(click: Click) = throw IllegalStateException("secret detail")
            })

            repeat(3) { listener.on(LinkFollowed(ShortCode("abc"), "x", "", "")) }

            assertThat(registry.counter("analytics.clicks.dropped", "reason", "error").count()).isEqualTo(3.0)
            assertThat(log.events).hasSize(1)
            assertThat(log.events.single().message).contains("IllegalStateException").doesNotContain("secret detail")
        }
    }

    @Test
    fun `logs again when counting fails after it had recovered`() {
        LogCapture(ClickListener::class.java.name).use { log ->
            var broken = true
            val listener = listener(object : ClickRecorder {
                override fun record(click: Click) {
                    check(!broken) { "down" }
                    recorded += click
                }
            })

            listener.on(LinkFollowed(ShortCode("abc"), "x", "", ""))
            broken = false
            repeat(2) { listener.on(LinkFollowed(ShortCode("abc"), "x", "", "")) }
            broken = true
            listener.on(LinkFollowed(ShortCode("abc"), "x", "", ""))

            assertThat(log.events).hasSize(2)
            assertThat(recorded).hasSize(2)
        }
    }
}
