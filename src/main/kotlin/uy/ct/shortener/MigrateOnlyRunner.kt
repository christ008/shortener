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
 * Lets the image that serves requests also apply the schema and stop, so the init container of the Deployment can
 * run the migrations with credentials the application container never receives. Flyway runs while the context
 * starts, before any runner, so by the time this one is called the schema is up to date; with
 * `shortener.migrate-only` set it ends the process, and a migration that fails never gets here because the context
 * fails to start and the process exits with an error.
 *
 * It reads the property when it runs instead of being switched on by it (`@ConditionalOnProperty`): a native image
 * decides at build time which beans exist, so a bean that depends on a property set only when the container starts
 * would be left out or left in for good.
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
