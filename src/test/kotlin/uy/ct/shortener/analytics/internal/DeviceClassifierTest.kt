package uy.ct.shortener.analytics.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * What a `User-Agent` has to say for a click to be a visitor on a desktop, on a phone, or not a visitor at all. The headers are
 * the ones the clients send.
 */
class DeviceClassifierTest {

    private fun classify(userAgent: String) = DeviceClassifier.classify(userAgent)

    @Test
    fun `a desktop browser is a desktop`() {
        assertThat(classify("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")).isEqualTo(DeviceClass.DESKTOP)
        assertThat(classify("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:131.0) Gecko/20100101 Firefox/131.0")).isEqualTo(DeviceClass.DESKTOP)
    }

    @Test
    fun `a phone or a tablet is mobile`() {
        assertThat(classify("Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1")).isEqualTo(DeviceClass.MOBILE)
        assertThat(classify("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36")).isEqualTo(DeviceClass.MOBILE)
        assertThat(classify("Mozilla/5.0 (iPad; CPU OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Safari/604.1")).isEqualTo(DeviceClass.MOBILE)
    }

    @Test
    fun `crawlers, link previewers and command line clients are bots`() {
        listOf(
            "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
            "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)",
            "WhatsApp/2.23.20.0 A",
            "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
            "curl/8.5.0",
            "Wget/1.21.4",
            "python-requests/2.32.0",
            "Go-http-client/2.0",
            "Mozilla/5.0 (X11; Linux x86_64) HeadlessChrome/130.0 Safari/537.36",
        ).forEach { assertThat(classify(it)).describedAs(it).isEqualTo(DeviceClass.BOT) }
    }

    @Test
    fun `a bot that names a phone is still a bot`() {
        assertThat(classify("Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X) AppleWebKit/537.36 (compatible; Googlebot/2.1)")).isEqualTo(DeviceClass.BOT)
    }

    @Test
    fun `a request without a user agent is a bot, because browsers always send one`() {
        assertThat(classify("")).isEqualTo(DeviceClass.BOT)
        assertThat(classify("   ")).isEqualTo(DeviceClass.BOT)
    }

    @Test
    fun `case does not matter`() {
        assertThat(classify("MOZILLA/5.0 (IPHONE; CPU IPHONE OS 18_0 LIKE MAC OS X) MOBILE/15E148")).isEqualTo(DeviceClass.MOBILE)
        assertThat(classify("MOZILLA/5.0 (COMPATIBLE; GOOGLEBOT/2.1)")).isEqualTo(DeviceClass.BOT)
        assertThat(classify("MOZILLA/5.0 (WINDOWS NT 10.0; WIN64; X64) CHROME/130.0")).isEqualTo(DeviceClass.DESKTOP)
    }

    @Test
    fun `reads only the start of a long header`() {
        val padding = "x".repeat(600)

        assertThat(classify("Mozilla/5.0 (X11; Linux) $padding Googlebot")).isEqualTo(DeviceClass.DESKTOP)
        assertThat(classify("Googlebot $padding")).isEqualTo(DeviceClass.BOT)
    }

    @Test
    fun `the database stores the three values its constraint allows, and each reads back as its class`() {
        assertThat(DeviceClass.entries.map { it.value }).containsExactly("desktop", "mobile", "bot")
        DeviceClass.entries.forEach { assertThat(DeviceClass.of(it.value)).isSameAs(it) }
        assertFailsWith<NoSuchElementException> { DeviceClass.of("tablet") }
    }
}
