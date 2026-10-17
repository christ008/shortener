package uy.ct.shortener.analytics.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import uy.ct.shortener.analytics.DeviceCounts
import uy.ct.shortener.analytics.Granularity
import uy.ct.shortener.analytics.HostVisitors
import uy.ct.shortener.analytics.InvalidStatsWindowException
import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.LanguageVisitors
import uy.ct.shortener.analytics.Languages
import uy.ct.shortener.analytics.Referrer
import uy.ct.shortener.analytics.Referrers
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

    private val breakdownRows = mutableListOf<BreakdownRow>()

    private val reads = mutableListOf<Pair<ShortCode, StatsWindow>>()

    private val breakdownReads = mutableListOf<Pair<ShortCode, StatsWindow>>()

    private val repository = object : ClickStatsRepository {
        override fun read(shortCode: ShortCode, window: StatsWindow): List<ClickRow> {
            reads += shortCode to window
            return rows.toList()
        }

        override fun breakdown(shortCode: ShortCode, days: StatsWindow): List<BreakdownRow> {
            breakdownReads += shortCode to days
            return breakdownRows.toList()
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

    private val clock = MutableClock(Instant.parse("2026-10-14T09:30:00Z"))

    private val service = DefaultClickStatsService(links, repository, clock)

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

    @Test
    fun `the breakdown covers the whole UTC days the window touches`() {
        val stats = service.stats(known, StatsWindow.of(Granularity.Hour, at("2026-10-12T22:00:00Z"), at("2026-10-13T02:00:00Z")))

        assertThat(breakdownReads.single().second).isEqualTo(StatsWindow(Granularity.Day, at("2026-10-12T00:00:00Z"), at("2026-10-14T00:00:00Z")))
        assertThat(stats.breakdown.start).isEqualTo(at("2026-10-12T00:00:00Z"))
        assertThat(stats.breakdown.end).isEqualTo(at("2026-10-14T00:00:00Z"))
    }

    @Test
    fun `shows the hosts with the most visitors first, ties by name, beside those that came direct`() {
        breakdownRows += listOf(
            BreakdownRow.FromReferrer(Referrer.Host("b.example"), 3),
            BreakdownRow.FromReferrer(Referrer.Host("a.example"), 3),
            BreakdownRow.FromReferrer(Referrer.Host("t.co"), 9),
            BreakdownRow.FromReferrer(Referrer.Direct, 4),
        )

        val referrers = service.stats(known, window).breakdown.referrers

        assertThat(referrers.hosts).containsExactly(HostVisitors("t.co", 9), HostVisitors("a.example", 3), HostVisitors("b.example", 3))
        assertThat(referrers.direct).isEqualTo(4)
        assertThat(referrers.other).isZero()
    }

    @Test
    fun `adds the hosts past the top 20 to other, with what instances already counted as other`() {
        breakdownRows += (1..25).map { BreakdownRow.FromReferrer(Referrer.Host("h$it.example"), 100L - it) }
        breakdownRows += BreakdownRow.FromReferrer(Referrer.Other, 7)

        val referrers = service.stats(known, window).breakdown.referrers

        assertThat(referrers.hosts).hasSize(20)
        assertThat(referrers.hosts.first()).isEqualTo(HostVisitors("h1.example", 99))
        assertThat(referrers.hosts.last()).isEqualTo(HostVisitors("h20.example", 80))
        assertThat(referrers.other).isEqualTo(7L + (21..25).sumOf { 100L - it })
    }

    @Test
    fun `shows every language, most first, and those it could not read`() {
        breakdownRows += listOf(
            BreakdownRow.InLanguage(Language.Code("en"), 2),
            BreakdownRow.InLanguage(Language.Code("es"), 5),
            BreakdownRow.InLanguage(Language.Unknown, 1),
        )

        val languages = service.stats(known, window).breakdown.languages

        assertThat(languages.codes).containsExactly(LanguageVisitors("es", 5), LanguageVisitors("en", 2))
        assertThat(languages.unknown).isEqualTo(1)
    }

    @Test
    fun `adds up a host or a language that comes in more than one row`() {
        breakdownRows += listOf(
            BreakdownRow.FromReferrer(Referrer.Host("t.co"), 2),
            BreakdownRow.FromReferrer(Referrer.Host("t.co"), 3),
            BreakdownRow.InLanguage(Language.Code("es"), 1),
            BreakdownRow.InLanguage(Language.Code("es"), 4),
        )

        val breakdown = service.stats(known, window).breakdown

        assertThat(breakdown.referrers.hosts).containsExactly(HostVisitors("t.co", 5))
        assertThat(breakdown.languages.codes).containsExactly(LanguageVisitors("es", 5))
    }

    @Test
    fun `a link nobody followed has an empty breakdown, not an absent one`() {
        val breakdown = service.stats(known, window).breakdown

        assertThat(breakdown.referrers).isEqualTo(Referrers(0, emptyList(), 0))
        assertThat(breakdown.languages).isEqualTo(Languages(emptyList(), 0))
    }

    @Test
    fun `reads no breakdown for a link the caller may not see`() {
        assertFailsWith<ShortLinkNotFoundException> { service.stats(ShortCode("other-code"), window) }

        assertThat(breakdownReads).isEmpty()
    }

    @Test
    fun `hours are kept for 90 days from the start of today, and a window that starts earlier is refused before anything is read`() {
        val keptSince = at("2026-07-16T00:00:00Z")

        assertThat(service.stats(known, StatsWindow(Granularity.Hour, keptSince, keptSince.plusSeconds(3_600))).points).hasSize(1)
        val failure = assertFailsWith<InvalidStatsWindowException> {
            service.stats(known, StatsWindow(Granularity.Hour, keptSince.minusSeconds(3_600), keptSince.plusSeconds(3_600)))
        }

        assertThat(failure.body.detail).contains("Hours are kept for 90 days, since 2026-07-16T00:00:00Z", "Ask by day for older ones")
        assertThat(reads).hasSize(1)
    }

    @Test
    fun `days are kept for 730 days, and a window that starts earlier is refused`() {
        val keptSince = at("2024-10-14T00:00:00Z")

        assertThat(service.stats(known, StatsWindow(Granularity.Day, keptSince, keptSince.plusSeconds(86_400))).points).hasSize(1)
        val failure = assertFailsWith<InvalidStatsWindowException> {
            service.stats(known, StatsWindow(Granularity.Day, keptSince.minusSeconds(86_400), keptSince.plusSeconds(86_400)))
        }

        assertThat(failure.body.detail).contains("Days are kept for 730 days, since 2024-10-14T00:00:00Z", "Older ones are not kept")
    }

    @Test
    fun `a refused window says nothing about the link, which is not even read`() {
        assertFailsWith<InvalidStatsWindowException> {
            service.stats(ShortCode("other-code"), StatsWindow(Granularity.Hour, at("2026-01-01T00:00:00Z"), at("2026-01-01T01:00:00Z")))
        }

        assertThat(reads).isEmpty()
        assertThat(breakdownReads).isEmpty()
    }
}
