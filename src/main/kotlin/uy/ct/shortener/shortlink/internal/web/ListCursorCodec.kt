package uy.ct.shortener.shortlink.internal.web

import uy.ct.shortener.shortlink.InvalidPagingException
import uy.ct.shortener.shortlink.LinkCursor
import uy.ct.shortener.shortlink.LinkOrder
import uy.ct.shortener.shortlink.ShortCode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * The text a client holds between two pages: `v1.<order>.<creation time in microseconds>.<short code>`, in base64url
 * so it is opaque to them and safe in a URL. The time is in microseconds because that is what Postgres stores; a coarser
 * time would make a page skip or repeat the links created within the same millisecond as its last one. The sort is in
 * it, so a cursor from a newest-first listing is refused by an oldest-first one instead of silently jumping to the
 * wrong place. It is only a position: what the caller may see is decided on every request from its token, never from
 * the cursor, so one made up by hand can at worst land somewhere else in the caller's own listing.
 */
internal object ListCursorCodec {

    private const val VERSION = "v1"
    private const val MAX_LENGTH = 200

    fun encode(cursor: LinkCursor, order: LinkOrder): String {
        val micros = ChronoUnit.MICROS.between(Instant.EPOCH, cursor.createdAt)
        val plain = "$VERSION.${order.code()}.$micros.${cursor.shortCode.value}"
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.toByteArray(Charsets.UTF_8))
    }

    fun decode(token: String, order: LinkOrder): LinkCursor {
        val parts = try {
            if (token.length > MAX_LENGTH) throw IllegalArgumentException("too long")
            String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8).split('.')
        } catch (ex: IllegalArgumentException) {
            throw invalid()
        }
        if (parts.size != 4 || parts[0] != VERSION) throw invalid()
        if (parts[1] != order.code()) {
            throw InvalidPagingException("The cursor belongs to a listing in another order; use it with the sort it came from")
        }
        val micros = parts[2].toLongOrNull() ?: throw invalid()
        val shortCode = try {
            ShortCode(parts[3])
        } catch (ex: IllegalArgumentException) {
            throw invalid()
        }
        return LinkCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), shortCode)
    }

    private fun LinkOrder.code() = if (this == LinkOrder.NEWEST_FIRST) "d" else "a"

    private fun invalid() = InvalidPagingException("The cursor is not valid; pass the nextCursor of a previous response unchanged")
}
