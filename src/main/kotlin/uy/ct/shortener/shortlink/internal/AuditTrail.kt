package uy.ct.shortener.shortlink.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.spi.LoggingEventBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import uy.ct.shortener.shortlink.Actor
import uy.ct.shortener.shortlink.CreatedByFilter
import uy.ct.shortener.shortlink.ShortLink

/**
 * The record of what clients did to links, for whoever has to answer "who did that".
 *
 * - [LoggingAuditTrail] writes one line per create and per disable, and per listing that reaches another client's links,
 *   on the logger `uy.ct.shortener.audit`, and counts each in `shortener.audit.events` tagged with the kind.
 * - A line carries the short code, who acted and the owner, and for a create the target's host. Never the target's
 *   path or query, which can hold a credential.
 * - An administrator acting on another client's link is its own kind, `disabled_by_admin` or `listed_others`, because
 *   that is the one an alert and a reviewer look for.
 * - [NoAuditTrail] records nothing, for tests that are about something else.
 */
interface AuditTrail {

    fun created(link: ShortLink, custom: Boolean)

    fun disabled(link: ShortLink, by: Actor.Client)

    fun listed(filter: CreatedByFilter)
}

object NoAuditTrail : AuditTrail {
    override fun created(link: ShortLink, custom: Boolean) = Unit

    override fun disabled(link: ShortLink, by: Actor.Client) = Unit

    override fun listed(filter: CreatedByFilter) = Unit
}

@Component
class LoggingAuditTrail(
    private val meters: MeterRegistry,
    private val logger: Logger,
    private val caller: () -> String?,
) : AuditTrail {

    @Autowired
    constructor(meters: MeterRegistry) : this(meters, LoggerFactory.getLogger(LOGGER_NAME), { SecurityContextHolder.getContext().authentication?.name })

    override fun created(link: ShortLink, custom: Boolean) {
        record(CREATED, logger.atInfo(), "Link created")
            .addKeyValue("shortlink.code", link.shortCode.value)
            .addKeyValue("shortlink.custom_code", custom)
            .addKeyValue("shortlink.target_host", link.targetUrl.host.orEmpty())
            .addKeyValue("owner", link.createdBy.label())
            .log()
    }

    override fun disabled(link: ShortLink, by: Actor.Client) {
        val onBehalf = !link.isCreatedBy(by)
        record(if (onBehalf) DISABLED_BY_ADMIN else DISABLED, if (onBehalf) logger.atWarn() else logger.atInfo(), "Link disabled")
            .addKeyValue("shortlink.code", link.shortCode.value)
            .addKeyValue("actor", by.name)
            .addKeyValue("owner", link.createdBy.label())
            .log()
    }

    override fun listed(filter: CreatedByFilter) {
        val actor = caller().orEmpty()
        val others = when (filter) {
            CreatedByFilter.Anyone -> "everyone"
            is CreatedByFilter.Only -> filter.client.takeUnless { it == actor } ?: return
        }
        record(LISTED_OTHERS, logger.atWarn(), "Links of other clients listed")
            .addKeyValue("actor", actor)
            .addKeyValue("owner", others)
            .log()
    }

    private fun Actor.label() = when (this) {
        is Actor.Client -> name
        Actor.Unknown -> "unknown"
    }

    private fun record(kind: String, event: LoggingEventBuilder, message: String): LoggingEventBuilder {
        meters.counter(METRIC, "type", kind).increment()
        return event.setMessage(message).addKeyValue("event.category", "audit").addKeyValue("event.action", kind)
    }

    companion object {
        const val LOGGER_NAME = "uy.ct.shortener.audit"
        const val METRIC = "shortener.audit.events"
        const val CREATED = "created"
        const val DISABLED = "disabled"
        const val DISABLED_BY_ADMIN = "disabled_by_admin"
        const val LISTED_OTHERS = "listed_others"
    }
}
