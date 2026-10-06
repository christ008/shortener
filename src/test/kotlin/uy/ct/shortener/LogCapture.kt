package uy.ct.shortener

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Collects what one logger writes while a test runs, with the key/value pairs of each line, so a test can say what an
 * event carries and, as importantly, what it does not.
 */
class LogCapture(loggerName: String) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(loggerName) as Logger

    private val appender = ListAppender<ILoggingEvent>().also {
        it.start()
        logger.addAppender(it)
        logger.level = Level.INFO
    }

    val events: List<Event> get() = appender.list.map(::Event)

    override fun close() {
        logger.detachAppender(appender)
    }

    class Event(private val source: ILoggingEvent) {
        val message: String get() = source.formattedMessage

        val level: Level get() = source.level

        val fields: Map<String, Any?> get() = source.keyValuePairs.orEmpty().associate { it.key to it.value }

        override fun toString() = "$message $fields"
    }
}
