package uy.ct.shortener.analytics.internal.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.format.support.DefaultFormattingConversionService
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import uy.ct.shortener.analytics.Breakdown
import uy.ct.shortener.analytics.ClickPoint
import uy.ct.shortener.analytics.ClickStats
import uy.ct.shortener.analytics.ClickStatsService
import uy.ct.shortener.analytics.DeviceCounts
import uy.ct.shortener.analytics.Granularity
import uy.ct.shortener.analytics.HostVisitors
import uy.ct.shortener.analytics.LanguageVisitors
import uy.ct.shortener.analytics.Languages
import uy.ct.shortener.analytics.Referrers
import uy.ct.shortener.analytics.StatsWindow
import uy.ct.shortener.analytics.internal.MutableClock
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLinkNotFoundException
import java.time.Instant

/**
 * The shape of the stats API: the controller in front of a fake service, with no Spring context and no database. It says which
 * window a request becomes, what the answer looks like and which requests are refused. Who may ask is the `shortlink` module's
 * rule and `ClickStatsIntegrationTest` covers it against a running server.
 */
class ClickStatsWebTest {

    private val clock = MutableClock(Instant.parse("2026-10-12T10:15:00Z"))

    private val asked = mutableListOf<Pair<ShortCode, StatsWindow>>()

    private val counts = mutableMapOf<Instant, DeviceCounts>()

    private var referrers = Referrers(0, emptyList(), 0)

    private var languages = Languages(emptyList(), 0)

    private val service = object : ClickStatsService {
        override fun stats(shortCode: ShortCode, window: StatsWindow): ClickStats {
            if (shortCode.value == "missing") throw ShortLinkNotFoundException(shortCode)
            asked += shortCode to window
            val days = StatsWindow.of(Granularity.Day, window.start, window.end)
            return ClickStats(shortCode, window, window.buckets.map { ClickPoint(it, counts.getOrDefault(it, DeviceCounts.NONE)) }, Breakdown(days.start, days.end, referrers, languages))
        }
    }

    private val mvc: MockMvc = MockMvcBuilders
        .standaloneSetup(ClickStatsController(service, clock))
        .setConversionService(DefaultFormattingConversionService().apply { addConverter(String::class.java, ShortCode::class.java) { ShortCode(it) } })
        .build()

    private fun stats(query: String = "", code: String = "abc1234"): MvcResult = mvc.perform(get("/api/short-links/$code/stats$query")).andReturn()

    private fun at(text: String) = Instant.parse(text)

    @Test
    fun `without a window it answers the last 30 days, a point for each, up to now`() {
        val result = stats()

        val window = asked.single().second
        assertThat(result.response.status).isEqualTo(200)
        assertThat(window.granularity).isEqualTo(Granularity.Day)
        assertThat(window.start).isEqualTo(at("2026-09-12T00:00:00Z"))
        assertThat(window.end).isEqualTo(at("2026-10-13T00:00:00Z"))
        assertThat(window.buckets).hasSize(31)
    }

    @Test
    fun `by the hour it answers the last 48 hours`() {
        stats("?granularity=hour")

        val window = asked.single().second
        assertThat(window.granularity).isEqualTo(Granularity.Hour)
        assertThat(window.start).isEqualTo(at("2026-10-10T10:00:00Z"))
        assertThat(window.end).isEqualTo(at("2026-10-12T11:00:00Z"))
    }

    @Test
    fun `takes the window the client asks for, widened to whole buckets`() {
        stats("?granularity=hour&from=2026-10-12T08:30:00Z&to=2026-10-12T10:00:00Z")

        val window = asked.single().second
        assertThat(window.start).isEqualTo(at("2026-10-12T08:00:00Z"))
        assertThat(window.end).isEqualTo(at("2026-10-12T10:00:00Z"))
    }

    @Test
    fun `a window with only an end reaches back the default span from it`() {
        stats("?to=2026-06-01T00:00:00Z")

        val window = asked.single().second
        assertThat(window.end).isEqualTo(at("2026-06-01T00:00:00Z"))
        assertThat(window.start).isEqualTo(at("2026-05-02T00:00:00Z"))
    }

    @Test
    fun `answers the link, the window it covered, the total, a point for every bucket and the breakdown of whole days`() {
        counts[at("2026-10-12T09:00:00Z")] = DeviceCounts(desktop = 5, mobile = 3, bot = 2)
        counts[at("2026-10-12T10:00:00Z")] = DeviceCounts(desktop = 1, mobile = 0, bot = 0)
        referrers = Referrers(direct = 4, hosts = listOf(HostVisitors("t.co", 3)), other = 2)
        languages = Languages(codes = listOf(LanguageVisitors("es", 6)), unknown = 3)

        val body = stats("?granularity=hour&from=2026-10-12T09:00:00Z&to=2026-10-12T11:00:00Z").response.contentAsString

        assertThat(body).isEqualTo(
            """{"shortCode":"abc1234","granularity":"hour","from":"2026-10-12T09:00:00Z","to":"2026-10-12T11:00:00Z",""" +
                """"total":{"visitors":9,"desktop":6,"mobile":3,"bot":2},""" +
                """"series":[{"start":"2026-10-12T09:00:00Z","visitors":8,"desktop":5,"mobile":3,"bot":2},""" +
                """{"start":"2026-10-12T10:00:00Z","visitors":1,"desktop":1,"mobile":0,"bot":0}],""" +
                """"breakdown":{"from":"2026-10-12T00:00:00Z","to":"2026-10-13T00:00:00Z",""" +
                """"referrers":{"direct":4,"hosts":[{"host":"t.co","visitors":3}],"other":2},""" +
                """"languages":{"codes":[{"code":"es","visitors":6}],"unknown":3}}}""",
        )
    }

    @Test
    fun `refuses a granularity it does not know, a window that is empty or too wide, and a time that is not one`() {
        listOf(
            "?granularity=week",
            "?from=2026-10-12T10:00:00Z&to=2026-10-12T10:00:00Z",
            "?from=2026-10-13T00:00:00Z&to=2026-10-12T00:00:00Z",
            "?granularity=hour&from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z",
            "?from=yesterday",
        ).forEach { query ->
            assertThat(stats(query).response.status).describedAs(query).isEqualTo(400)
        }
        assertThat(asked).isEmpty()
    }

    @Test
    fun `a link that cannot be seen is a 404`() {
        assertThat(stats(code = "missing").response.status).isEqualTo(404)
    }
}
