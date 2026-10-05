package uy.ct.shortener.shortlink.internal.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uy.ct.shortener.shortlink.InvalidPagingException
import uy.ct.shortener.shortlink.LinkCursor
import uy.ct.shortener.shortlink.LinkOrder
import uy.ct.shortener.shortlink.ShortCode
import java.time.Instant
import java.util.Base64
import kotlin.test.assertFailsWith

/**
 * The cursor is what a client carries from one page to the next. It has to give back the position it was made from
 * exactly, to the microsecond, because a position a microsecond off skips or repeats a link; it belongs to the sort it
 * was made for; and anything else a client sends in its place is a 400, never an exception from further down.
 */
class ListCursorCodecTest {

    private val cursor = LinkCursor(Instant.parse("2026-03-04T05:06:07.123456Z"), ShortCode("aB3-x_9"))

    private fun token(plain: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(plain.toByteArray())

    @Test
    fun `gives back the position it was made from, to the microsecond, for either sort`() {
        LinkOrder.entries.forEach { order ->
            assertThat(ListCursorCodec.decode(ListCursorCodec.encode(cursor, order), order)).isEqualTo(cursor)
        }
    }

    @Test
    fun `is opaque and safe in a URL`() {
        val encoded = ListCursorCodec.encode(cursor, LinkOrder.NEWEST_FIRST)

        assertThat(encoded).matches("[A-Za-z0-9_-]+").doesNotContain("aB3-x_9")
    }

    @Test
    fun `refuses a cursor from the other sort, which would jump to the wrong place`() {
        val encoded = ListCursorCodec.encode(cursor, LinkOrder.NEWEST_FIRST)

        val failure = assertFailsWith<InvalidPagingException> { ListCursorCodec.decode(encoded, LinkOrder.OLDEST_FIRST) }

        assertThat(failure.body.detail).contains("another order")
    }

    @Test
    fun `refuses what the service did not issue, as a bad request`() {
        listOf(
            "",
            "not base64 !!",
            "x".repeat(300),
            token("v1.d.123"),
            token("v2.d.1772600767123456.aB3-x_9"),
            token("v1.d.notanumber.aB3-x_9"),
            token("v1.d.1772600767123456.no spaces"),
            token("v1.d.1772600767123456.ab"),
            token("v1.d.1772600767123456.aB3-x_9.extra"),
            token("v1.x.1772600767123456.aB3-x_9"),
        ).forEach { garbage ->
            assertFailsWith<InvalidPagingException>("expected '$garbage' to be refused") { ListCursorCodec.decode(garbage, LinkOrder.NEWEST_FIRST) }
        }
    }
}
