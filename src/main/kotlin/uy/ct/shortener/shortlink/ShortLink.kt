package uy.ct.shortener.shortlink

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.net.URI
import java.time.Instant

/** A short code mapped to a destination URL. Immutable once created - repointing an existing code is a future, separate feature. */
@Entity
@Table(name = "short_link")
class ShortLink(

    @Column(name = "short_code", nullable = false, unique = true, updatable = false, columnDefinition = "TEXT")
    val shortCode: ShortCode,

    @Column(name = "target_url", nullable = false, updatable = false, columnDefinition = "TEXT")
    val targetUrl: URI,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
) {

    /** [GenerationType.IDENTITY] over `SEQUENCE`: simplest for a single-table aggregate, at the cost of insert batching. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    var id: Long? = null
        protected set

    init {
        requireValidTargetUrl(targetUrl)
    }

    /** Identity-based, not `data class`, equality: keeps `copy()`/`componentN()` off an immutable entity and avoids two unsaved rows comparing equal. */
    override fun equals(other: Any?): Boolean =
        this === other || (other is ShortLink && id != null && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()

    override fun toString(): String = "ShortLink(id=$id, shortCode=$shortCode)"

    companion object {
        /** Exposed separately so callers can validate a target URL before paying for code generation. */
        fun requireValidTargetUrl(targetUrl: URI) {
            require(targetUrl.isAbsolute) { "targetUrl must be an absolute URI, was '$targetUrl'" }
            require(targetUrl.scheme == "http" || targetUrl.scheme == "https") {
                "targetUrl scheme must be http or https, was '${targetUrl.scheme}'"
            }
        }
    }
}
