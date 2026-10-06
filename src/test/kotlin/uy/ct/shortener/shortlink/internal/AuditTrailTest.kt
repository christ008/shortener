package uy.ct.shortener.shortlink.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import uy.ct.shortener.LogCapture
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.CreatedByFilter
import uy.ct.shortener.shortlink.ShortCode
import uy.ct.shortener.shortlink.ShortLink
import java.net.URI
import java.time.Instant

/**
 * What the audit trail writes for each thing a client does to a link, and what it leaves out.
 */
class AuditTrailTest {

    private val meters = SimpleMeterRegistry()

    private var caller: String? = "admin-client"

    private val trail = LoggingAuditTrail(meters, LoggerFactory.getLogger(LoggingAuditTrail.LOGGER_NAME), caller = { caller })

    private val link = ShortLink(
        ShortCode("abc1234"),
        URI.create("https://shop.example.com/cart?session=SECRET-IN-QUERY"),
        Actor.Client("user:alice"),
        Instant.parse("2026-10-06T00:00:00Z"),
    )

    private fun count(type: String) = meters.counter(LoggingAuditTrail.METRIC, "type", type).count()

    @Test
    fun `a create records the code, the owner and the host of the target, and never its path or query`() {
        LogCapture(LoggingAuditTrail.LOGGER_NAME).use { log ->
            trail.created(link, custom = true)

            val line = log.events.single()
            assertThat(line.fields).containsEntry("event.action", "created")
                .containsEntry("shortlink.code", "abc1234")
                .containsEntry("shortlink.custom_code", true)
                .containsEntry("shortlink.target_host", "shop.example.com")
                .containsEntry("owner", "user:alice")
            assertThat(line.toString()).doesNotContain("SECRET").doesNotContain("/cart")
        }
        assertThat(count("created")).isEqualTo(1.0)
    }

    @Test
    fun `an owner disabling their own link is routine`() {
        LogCapture(LoggingAuditTrail.LOGGER_NAME).use { log ->
            trail.disabled(link, by = Actor.Client("user:alice"))

            assertThat(log.events.single().level.toString()).isEqualTo("INFO")
            assertThat(log.events.single().fields).containsEntry("event.action", "disabled")
        }
        assertThat(count("disabled")).isEqualTo(1.0)
        assertThat(count("disabled_by_admin")).isEqualTo(0.0)
    }

    @Test
    fun `an administrator disabling another client's link is a warning of its own kind`() {
        LogCapture(LoggingAuditTrail.LOGGER_NAME).use { log ->
            trail.disabled(link, by = Actor.Client("admin-client"))

            val line = log.events.single()
            assertThat(line.level.toString()).isEqualTo("WARN")
            assertThat(line.fields).containsEntry("event.action", "disabled_by_admin")
                .containsEntry("actor", "admin-client")
                .containsEntry("owner", "user:alice")
        }
        assertThat(count("disabled_by_admin")).isEqualTo(1.0)
    }

    @Test
    fun `listing everyone's links or another client's is recorded, listing your own is not`() {
        LogCapture(LoggingAuditTrail.LOGGER_NAME).use { log ->
            trail.listed(CreatedByFilter.Only("admin-client"))
            assertThat(log.events).isEmpty()

            trail.listed(CreatedByFilter.Anyone)
            trail.listed(CreatedByFilter.Only("user:alice"))

            assertThat(log.events).hasSize(2)
            assertThat(log.events.map { it.fields["owner"] }).containsExactly("everyone", "user:alice")
        }
        assertThat(count("listed_others")).isEqualTo(2.0)
    }

    @Test
    fun `a link of unknown creator is named so`() {
        LogCapture(LoggingAuditTrail.LOGGER_NAME).use { log ->
            trail.disabled(link.copy(createdBy = Actor.Unknown), by = Actor.Client("admin-client"))

            assertThat(log.events.single().fields).containsEntry("owner", "unknown")
        }
    }
}
