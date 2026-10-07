package uy.ct.shortener

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

/**
 * Applies the schema and ends the process when `shortener.migrate-only` is set, for the one-shot migration job.
 *
 * - Flyway runs while the context starts, before any runner, so the schema is current when this runs.
 * - A failed migration fails the context, so the process exits with an error.
 * - The property is read when the runner runs, not through `@ConditionalOnProperty` (native image: docs/INTERNALS.md, "What the native image had to be taught").
 */
@Component
class MigrateOnlyRunner(private val context: ConfigurableApplicationContext, environment: Environment) : ApplicationRunner {

    private val migrateOnly = environment.getProperty("shortener.migrate-only", Boolean::class.java, false)

    override fun run(args: ApplicationArguments) {
        if (!migrateOnly) return
        LoggerFactory.getLogger(MigrateOnlyRunner::class.java).info("The schema is up to date and shortener.migrate-only is set; exiting")
        terminate(SpringApplication.exit(context))
    }

    internal fun terminate(code: Int): Unit = exitProcess(code)
}
