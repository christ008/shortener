package uy.ct.shortener

import org.springframework.boot.flyway.autoconfigure.FlywayConnectionDetails
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Gives each Spring context its own empty database, on one throwaway Postgres (same version as `compose.yaml`) that all of them share.
 *
 * - Starting a Postgres took about a second of the second and a half a context needed, and the suite builds a dozen contexts, so
 *   the container starts once per JVM and is removed when it ends.
 * - A context gets a new database, initialised with the same `deploy/postgres/bootstrap.sql` as every other environment so the
 *   roles exist, which is also what keeps one context's tables and rows away from another's.
 * - Spring keeps every context it built, each with its own pool of connections, so the server allows more than the default hundred.
 * - The application still connects as the container's superuser; the roles are exercised by `DatabaseRolesTest`, which makes a
 *   database of its own as well.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    fun postgresConnectionDetails(): PostgresConnectionDetails = SharedPostgres.newDatabase()
}

class PostgresConnectionDetails(private val url: String, private val user: String, private val secret: String) :
    JdbcConnectionDetails, FlywayConnectionDetails {

    override fun getJdbcUrl() = url

    override fun getUsername() = user

    override fun getPassword() = secret

    override fun getDriverClassName(): String = "org.postgresql.Driver"
}

private object SharedPostgres {

    private const val MAX_CONNECTIONS = 400

    private val databases = AtomicInteger()

    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:18.6"))
            .withCommand("postgres", "-c", "max_connections=$MAX_CONNECTIONS")
            .also { it.start() }
    }

    @Synchronized
    fun newDatabase(): PostgresConnectionDetails {
        val name = "context_${databases.incrementAndGet()}"
        val postgres = container
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use {
            it.createStatement().execute("CREATE DATABASE $name")
        }
        val url = postgres.jdbcUrl.replace(Regex("/${postgres.databaseName}(?=\\?|$)"), "/$name")
        DriverManager.getConnection(url, postgres.username, postgres.password).use {
            it.createStatement().execute(Files.readString(Path.of("deploy/postgres/bootstrap.sql")))
        }
        return PostgresConnectionDetails(url, postgres.username, postgres.password)
    }
}
