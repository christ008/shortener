package uy.ct.shortener.shortlink.internal.persistence

import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uy.ct.shortener.TestcontainersConfiguration
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertFailsWith

/**
 * What separates the role that changes the schema from the one that serves requests, on a database provisioned the way
 * a deployment does it: `deploy/postgres/bootstrap.sql`, then Flyway as `shortener_migrator`. The application role can
 * do exactly what [JdbcShortLinkRepository] does and nothing else, and Flyway started with it finds nothing to apply,
 * which is what the application container does on every start. The statements it must not run are refused with
 * `insufficient_privilege` (`42501`), so a statement that fails for another reason does not pass for the wrong one.
 * The test logs in as each role, because limits set on a role apply at login and `SET ROLE` would not show them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(TestcontainersConfiguration::class)
class DatabaseRolesTest {

    @Autowired
    lateinit var dataSource: DataSource

    private val database = "roles_" + UUID.randomUUID().toString().replace("-", "")
    private val stranger = "stranger_" + UUID.randomUUID().toString().replace("-", "")
    private val passwords = mapOf("shortener_app" to "app-pw", "shortener_migrator" to "migrator-pw", "shortener_exporter" to "exporter-pw", stranger to "stranger-pw")

    private lateinit var adminUrl: String
    private lateinit var adminUser: String
    private lateinit var adminPassword: String
    private val defaultDatabase get() = Regex("/([^/?]+)(\\?|$)").find(adminUrl.substringAfter("//"))!!.groupValues[1]

    private fun urlOf(name: String) = adminUrl.replace(Regex("^(jdbc:postgresql://[^/]+/)[^?]*"), "$1$name")

    private fun <T> asAdmin(name: String = database, block: (Connection) -> T): T =
        DriverManager.getConnection(urlOf(name), adminUser, adminPassword).use(block)

    private fun <T> asRole(role: String, block: (Connection) -> T): T =
        DriverManager.getConnection(urlOf(database), role, passwords.getValue(role)).use(block)

    private fun flywayAs(role: String) =
        Flyway.configure().dataSource(urlOf(database), role, passwords.getValue(role)).locations("classpath:db/migration").load()

    @BeforeAll
    fun provision() {
        val pool = dataSource.unwrap(HikariDataSource::class.java)
        adminUrl = pool.jdbcUrl
        adminUser = pool.username
        adminPassword = pool.password ?: ""
        asAdmin(defaultDatabase) { it.createStatement().execute("CREATE DATABASE $database") }
        asAdmin { connection ->
            connection.createStatement().execute(Files.readString(Path.of("deploy/postgres/bootstrap.sql")))
            connection.createStatement().execute("CREATE ROLE $stranger LOGIN")
            passwords.forEach { (role, password) -> connection.createStatement().execute("ALTER ROLE $role PASSWORD '$password'") }
        }
        flywayAs("shortener_migrator").migrate()
    }

    @AfterAll
    fun dropEverything() {
        asAdmin(defaultDatabase) {
            it.createStatement().execute("DROP DATABASE IF EXISTS $database WITH (FORCE)")
            it.createStatement().execute("DROP ROLE IF EXISTS $stranger")
        }
    }

    private fun assertRefused(connection: Connection, sql: String) {
        val failure = assertFailsWith<SQLException>("expected '$sql' to be refused") { connection.createStatement().execute(sql) }
        assertThat(failure.sqlState).describedAs("SQLSTATE of '$sql': ${failure.message}").isEqualTo("42501")
    }

    @Test
    fun `leaves the tables, and so the right to change them, with the migrator`() {
        val owners = asAdmin { connection ->
            connection.createStatement().executeQuery("SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'public'").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap()
            }
        }

        assertThat(owners).containsOnly(
            entry("short_link", "shortener_migrator"),
            entry("flyway_schema_history", "shortener_migrator"),
        )
    }

    @Test
    fun `lets the application role do what the repository does`() {
        asRole("shortener_app") { connection ->
            val insert = connection.prepareStatement(
                "INSERT INTO short_link (short_code, target_url, created_by) VALUES (?, ?, ?) ON CONFLICT (short_code) DO NOTHING RETURNING short_code, created_at",
            )
            insert.setString(1, "app-role")
            insert.setString(2, "https://example.com/app")
            insert.setString(3, "client")
            assertThat(insert.executeQuery().use { it.next() }).describedAs("inserted").isTrue()

            val select = connection.createStatement()
                .executeQuery("SELECT short_code, target_url, created_by, created_at, disabled_at, disabled_by FROM short_link WHERE short_code = 'app-role'")
            assertThat(select.use { it.next() }).describedAs("read back").isTrue()

            val disable = connection.createStatement().executeQuery(
                "UPDATE short_link SET disabled_at = COALESCE(disabled_at, now()), disabled_by = COALESCE(disabled_by, 'client') WHERE short_code = 'app-role' RETURNING short_code",
            )
            assertThat(disable.use { it.next() }).describedAs("disabled").isTrue()
        }
    }

    @Test
    fun `refuses the application role everything else`() {
        asRole("shortener_app") { connection ->
            listOf(
                "UPDATE short_link SET target_url = 'https://evil.example' WHERE short_code = 'x'",
                "UPDATE short_link SET short_code = 'y' WHERE short_code = 'x'",
                "UPDATE short_link SET created_by = 'someone else' WHERE short_code = 'x'",
                "DELETE FROM short_link WHERE short_code = 'x'",
                "TRUNCATE short_link",
                "DROP TABLE short_link",
                "ALTER TABLE short_link ADD COLUMN extra int",
                "CREATE INDEX extra_idx ON short_link (target_url)",
                "CREATE TABLE extra (id int)",
                "INSERT INTO flyway_schema_history (installed_rank, description, type, script, checksum, installed_by, execution_time, success) VALUES (99, 'x', 'SQL', 'x', 0, 'x', 0, true)",
                "DELETE FROM flyway_schema_history",
            ).forEach { assertRefused(connection, it) }
        }
    }

    @Test
    fun `lets Flyway started with the application role find nothing to apply`() {
        val result = flywayAs("shortener_app").migrate()

        assertThat(result.success).isTrue()
        assertThat(result.migrationsExecuted).isZero()
    }

    @Test
    fun `applies the limits of each role when it logs in`() {
        fun settings(role: String) = asRole(role) { connection ->
            listOf("statement_timeout", "lock_timeout", "idle_in_transaction_session_timeout").associateWith { name ->
                connection.createStatement().executeQuery("SHOW $name").use { it.next(); it.getString(1) }
            }
        }

        assertThat(settings("shortener_app")).containsEntry("statement_timeout", "5s").containsEntry("lock_timeout", "2s")
            .containsEntry("idle_in_transaction_session_timeout", "10s")
        assertThat(settings("shortener_migrator")).containsEntry("statement_timeout", "0").containsEntry("lock_timeout", "10s")
    }

    @Test
    fun `lets the exporter read statistics but not the links`() {
        asRole("shortener_exporter") { connection ->
            assertThat(connection.createStatement().executeQuery("SELECT count(*) FROM pg_stat_database").use { it.next() }).isTrue()
            assertRefused(connection, "SELECT * FROM short_link")
        }
    }

    @Test
    fun `lets nobody else connect to the database`() {
        val failure = assertFailsWith<SQLException> { DriverManager.getConnection(urlOf(database), stranger, passwords.getValue(stranger)) }

        assertThat(failure.sqlState).describedAs(failure.message).isEqualTo("42501")
    }

    @Test
    fun `can be run again on a database that is already set up`() {
        asAdmin { connection -> connection.createStatement().execute(Files.readString(Path.of("deploy/postgres/bootstrap.sql"))) }

        assertThat(flywayAs("shortener_migrator").migrate().migrationsExecuted).isZero()
    }
}
