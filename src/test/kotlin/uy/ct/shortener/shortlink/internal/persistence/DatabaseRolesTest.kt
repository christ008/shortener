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
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import uy.ct.shortener.TestcontainersConfiguration
import uy.ct.shortener.analytics.Language
import uy.ct.shortener.analytics.Referrer
import uy.ct.shortener.analytics.internal.ClickCount
import uy.ct.shortener.analytics.internal.CountKey
import uy.ct.shortener.analytics.internal.DeviceClass
import uy.ct.shortener.analytics.internal.persistence.JdbcClickStore
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
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
        Flyway.configure().dataSource(urlOf(database), role, passwords.getValue(role)).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()

    @BeforeAll
    fun provision() {
        val pool = dataSource.unwrap(HikariDataSource::class.java)
        adminUrl = pool.jdbcUrl
        adminUser = pool.username
        adminPassword = pool.password ?: ""
        asAdmin(defaultDatabase) { it.createStatement().execute("create database $database") }
        asAdmin { connection ->
            connection.createStatement().execute(Files.readString(Path.of("deploy/postgres/bootstrap.sql")))
            connection.createStatement().execute("create role $stranger login")
            passwords.forEach { (role, password) -> connection.createStatement().execute("alter role $role password '$password'") }
        }
        flywayAs("shortener_migrator").migrate()
    }

    @AfterAll
    fun dropEverything() {
        asAdmin(defaultDatabase) {
            it.createStatement().execute("drop database if exists $database with (force)")
            it.createStatement().execute("drop role if exists $stranger")
        }
    }

    private fun assertRefused(connection: Connection, sql: String) {
        val failure = assertFailsWith<SQLException>("expected '$sql' to be refused") { connection.createStatement().execute(sql) }
        assertThat(failure.sqlState).describedAs("SQLSTATE of '$sql': ${failure.message}").isEqualTo("42501")
    }

    @Test
    fun `leaves the tables, and so the right to change them, with the migrator`() {
        val owners = asAdmin { connection ->
            connection.createStatement().executeQuery("select tablename, tableowner from pg_tables where schemaname = 'public'").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }.toMap()
            }
        }

        assertThat(owners).containsOnly(
            entry("short_link", "shortener_migrator"),
            entry("link_click_hourly", "shortener_migrator"),
            entry("link_click_daily", "shortener_migrator"),
            entry("link_click_daily_device", "shortener_migrator"),
            entry("flyway_schema_history", "shortener_migrator"),
        )
    }

    @Test
    fun `lets the application role do what the repository does`() {
        asRole("shortener_app") { connection ->
            val insert = connection.prepareStatement(
                "insert into short_link (short_code, target_url, created_by) values (?, ?, ?) on conflict (short_code) do nothing returning short_code, created_at",
            )
            insert.setString(1, "app-role")
            insert.setString(2, "https://example.com/app")
            insert.setString(3, "client")
            assertThat(insert.executeQuery().use { it.next() }).describedAs("inserted").isTrue()

            val select = connection.createStatement()
                .executeQuery("select short_code, target_url, created_by, created_at, disabled_at, disabled_by from short_link where short_code = 'app-role'")
            assertThat(select.use { it.next() }).describedAs("read back").isTrue()

            val disable = connection.createStatement().executeQuery(
                "update short_link set disabled_at = coalesce(disabled_at, now()), disabled_by = coalesce(disabled_by, 'client') where short_code = 'app-role' returning short_code",
            )
            assertThat(disable.use { it.next() }).describedAs("disabled").isTrue()
        }
    }

    @Test
    fun `lets the application role add to a count of clicks and read it, as the click store does`() {
        asRole("shortener_app") { connection ->
            val add = connection.prepareStatement(
                "insert into link_click_hourly (short_code, hour, device_class, clicks) values ('clicks-a', '2026-10-12T10:00:00Z', 'desktop', ?) " +
                    "on conflict (short_code, hour, device_class) do update set clicks = link_click_hourly.clicks + excluded.clicks",
            )
            repeat(2) {
                add.setLong(1, 3)
                add.executeUpdate()
            }

            val total = connection.createStatement().executeQuery("select clicks from link_click_hourly where short_code = 'clicks-a'")
            assertThat(total.use { it.next(); it.getLong(1) }).isEqualTo(6L)
        }
    }

    @Test
    fun `lets the application role write a batch of hourly and daily counts with the statement the click store uses`() {
        val store = JdbcClickStore(JdbcTemplate(DriverManagerDataSource(urlOf(database), "shortener_app", passwords.getValue("shortener_app"))))
        val day = Instant.parse("2026-10-12T10:00:00Z").epochSecond / 86_400
        val batch = listOf(
            ClickCount(CountKey.Hourly("store-role", Instant.parse("2026-10-12T10:00:00Z").epochSecond / 3_600, DeviceClass.MOBILE), 2),
            ClickCount(CountKey.ReferrerDaily("store-role", day, Referrer.Host("t.co")), 2),
            ClickCount(CountKey.LanguageDaily("store-role", day, Language.Code("es")), 2),
        )

        repeat(2) { store.add(batch) }

        asRole("shortener_app") { connection ->
            val daily = connection.createStatement().executeQuery("select sum(clicks) from link_click_daily where short_code = 'store-role'")
            assertThat(daily.use { it.next(); it.getLong(1) }).isEqualTo(8L)
        }
    }

    @Test
    fun `lets the application role fold and delete old counts only by running the retention function, with its fixed rules`() {
        asAdmin { connection ->
            connection.createStatement().execute(
                "insert into link_click_hourly (short_code, hour, device_class, clicks) " +
                    "values ('retained', ((now() at time zone 'UTC')::date - 100)::timestamp at time zone 'UTC', 'desktop', 4), " +
                    "('retained', now(), 'desktop', 1)",
            )
        }

        asRole("shortener_app") { connection ->
            val step = connection.createStatement().executeQuery("select busy, hours_folded, rows_deleted from analytics_retention()")
            assertThat(step.use { it.next(); it.getBoolean(1) to it.getLong(2) }).isEqualTo(false to 1L)
            val folded = connection.createStatement().executeQuery("select sum(clicks) from link_click_daily_device where short_code = 'retained'")
            assertThat(folded.use { it.next(); it.getLong(1) }).isEqualTo(4L)
            val left = connection.createStatement().executeQuery("select count(*) from link_click_hourly where short_code = 'retained'")
            assertThat(left.use { it.next(); it.getLong(1) }).isEqualTo(1L)
        }
    }

    @Test
    fun `refuses the application role to write the folded days itself, or to change the retention function`() {
        asRole("shortener_app") { connection ->
            listOf(
                "insert into link_click_daily_device (short_code, day, device_class, clicks) values ('x', current_date, 'desktop', 1)",
                "update link_click_daily_device set clicks = 0",
                "delete from link_click_daily_device",
                "truncate link_click_daily_device",
                "create or replace function analytics_retention(out busy boolean, out hours_folded bigint, out rows_deleted bigint) " +
                    "language sql as 'select false, 0::bigint, 0::bigint'",
                "alter function analytics_retention() security invoker",
                "drop function analytics_retention()",
            ).forEach { assertRefused(connection, it) }
        }
    }

    @Test
    fun `refuses the application role to delete a count or move it to another link or hour`() {
        asRole("shortener_app") { connection ->
            listOf(
                "delete from link_click_hourly",
                "truncate link_click_hourly",
                "update link_click_hourly set short_code = 'y'",
                "update link_click_hourly set hour = now()",
                "update link_click_hourly set device_class = 'bot'",
                "drop table link_click_hourly",
                "alter table link_click_hourly add column extra int",
                "delete from link_click_daily",
                "truncate link_click_daily",
                "update link_click_daily set short_code = 'y'",
                "update link_click_daily set day = current_date",
                "update link_click_daily set value = 'evil.example'",
                "update link_click_daily set dimension = 'language'",
                "drop table link_click_daily",
            ).forEach { assertRefused(connection, it) }
        }
    }

    @Test
    fun `refuses the application role everything else`() {
        asRole("shortener_app") { connection ->
            listOf(
                "update short_link set target_url = 'https://evil.example' where short_code = 'x'",
                "update short_link set short_code = 'y' where short_code = 'x'",
                "update short_link set created_by = 'someone else' where short_code = 'x'",
                "delete from short_link where short_code = 'x'",
                "truncate short_link",
                "drop table short_link",
                "alter table short_link add column extra int",
                "create index extra_idx on short_link (target_url)",
                "create table extra (id int)",
                "insert into flyway_schema_history (installed_rank, description, type, script, checksum, installed_by, execution_time, success) values (99, 'x', 'SQL', 'x', 0, 'x', 0, true)",
                "delete from flyway_schema_history",
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
                connection.createStatement().executeQuery("show $name").use { it.next(); it.getString(1) }
            }
        }

        assertThat(settings("shortener_app")).containsEntry("statement_timeout", "5s").containsEntry("lock_timeout", "2s")
            .containsEntry("idle_in_transaction_session_timeout", "10s")
        assertThat(settings("shortener_migrator")).containsEntry("statement_timeout", "0").containsEntry("lock_timeout", "10s")
    }

    @Test
    fun `lets the exporter read statistics but not the links`() {
        asRole("shortener_exporter") { connection ->
            assertThat(connection.createStatement().executeQuery("select count(*) from pg_stat_database").use { it.next() }).isTrue()
            assertRefused(connection, "select * from short_link")
            assertRefused(connection, "select * from link_click_hourly")
            assertRefused(connection, "select * from link_click_daily")
            assertRefused(connection, "select * from link_click_daily_device")
            assertRefused(connection, "select * from analytics_retention()")
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
