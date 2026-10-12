package uy.ct.shortener.analytics.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import uy.ct.shortener.analytics.DeviceCounts
import uy.ct.shortener.analytics.Granularity
import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.ClaimResult
import uy.ct.shortener.shortlink.CreatedByFilter
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import uy.ct.shortener.shortlink.ShortLinkNotFoundException
import uy.ct.shortener.shortlink.ShortLinkService
import java.net.URI
import java.time.Instant
import kotlin.test.assertFailsWith

/**
 * What the service makes of the rows it reads: a point for every bucket of the window, the classes of one bucket added into one
 * point, and nothing read for a link the caller may not see. The link rule is the `shortlink` module's, here a fake that knows some codes.
 */
class DefaultClickStatsServiceTest {

    private val known = ShortCode("known-code")

    private val rows = mutableListOf<ClickRow>()

    private val reads = mutableListOf<Pair<ShortCode, StatsWindow>>()

    private val repository = object : ClickStatsRepository {
        override fun read(shortCode: ShortCode, window: StatsWindow): List<ClickRow> {
            reads += shortCode to window
            return rows.toList()
        }
    }

    private val links = object : ShortLinkService {
        override fun get(shortCode: ShortCode): ShortLink =
            if (shortCode == known) ShortLink(known, URI.create("https://example.com"), Actor.Client("alice"), Instant.parse("2026-10-01T00:00:00Z"))
            else throw ShortLinkNotFoundException(shortCode)

        override fun shorten(targetUrl: String, createdBy: Actor.Client): ShortLink = error("not used")
        override fun claim(shortCode: ShortCode, targetUrl: String, createdBy: Actor.Client): ClaimResult = error("not used")
        override fun resolve(shortCode: ShortCode): ShortLink = error("not used")
        override fun list(filter: CreatedByFilter, pageable: Pageable): Page<ShortLink> = error("not used")
        override fun disable(shortCode: ShortCode, disabledBy: Actor.Client): ShortLink = error("not used")
    }

    private val service = DefaultClickStatsService(links, repository)

    private fun at(text: String) = Instant.parse(text)

    private val window = StatsWindow.of(Granularity.Hour, at("2026-10-12T10:00:00Z"), at("2026-10-12T13:00:00Z"))

    @Test
    fun `answers a point for every bucket, zero where nothing was counted`() {
        rows += ClickRow(at("2026-10-12T11:00:00Z"), DeviceClass.DESKTOP, 4)

        val stats = service.stats(known, window)

        assertThat(stats.points.map { it.start }).containsExactlyElementsOf(window.buckets)
        assertThat(stats.points.map { it.counts }).containsExactly(DeviceCounts.NONE, DeviceCounts(4, 0, 0), DeviceCounts.NONE)
    }

    @Test
    fun `puts each class in its own count and adds the classes of one bucket into one point`() {
        rows += listOf(
            ClickRow(at("2026-10-12T10:00:00Z"), DeviceClass.DESKTOP, 5),
            ClickRow(at("2026-10-12T10:00:00Z"), DeviceClass.MOBILE, 3),
            ClickRow(at("2026-10-12T10:00:00Z"), DeviceClass.BOT, 7),
            ClickRow(at("2026-10-12T12:00:00Z"), DeviceClass.MOBILE, 1),
        )

        val stats = service.stats(known, window)

        assertThat(stats.points.map { it.counts }).containsExactly(DeviceCounts(5, 3, 7), DeviceCounts.NONE, DeviceCounts(0, 1, 0))
        assertThat(stats.total).isEqualTo(DeviceCounts(5, 4, 7))
        assertThat(stats.total.visitors).isEqualTo(9)
    }

    @Test
    fun `ignores a row outside the window instead of failing`() {
        rows += ClickRow(at("2026-10-12T09:00:00Z"), DeviceClass.DESKTOP, 9)

        assertThat(service.stats(known, window).total).isEqualTo(DeviceCounts.NONE)
    }

    @Test
    fun `reads the counts of the code and window it was asked for`() {
        service.stats(known, window)

        assertThat(reads).containsExactly(known to window)
    }

    @Test
    fun `reads nothing for a link the caller may not see`() {
        assertFailsWith<ShortLinkNotFoundException> { service.stats(ShortCode("other-code"), window) }

        assertThat(reads).isEmpty()
    }

    @Test
    fun `a link nobody followed has a series of zeros, not an absent one`() {
        val stats = service.stats(known, window)

        assertThat(stats.points).hasSize(3)
        assertThat(stats.total).isEqualTo(DeviceCounts.NONE)
    }
}
