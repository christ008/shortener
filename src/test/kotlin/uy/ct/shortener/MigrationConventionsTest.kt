package uy.ct.shortener

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

/**
 * Migrations run while the previous version of the application is still serving, against a table that may hold
 * millions of rows, so a statement that is harmless on an empty table can stop every request. Measured on two million
 * links, adding a `CHECK` held an exclusive lock for 4.7 s (reads included) and an ordinary `CREATE INDEX` blocked
 * writes for 1.3 s; both grow with the table. This reads the migrations after [LAST_UNCHECKED_VERSION] for the
 * statements that cause that, and says what to write instead. It is a text check, not a SQL parser: it catches the
 * usual mistakes, and a migration that is safe for a reason it cannot see (a table created in the same migration, one
 * known to be small) says so with `-- unsafe-ok: <reason>`.
 */
class MigrationConventionsTest {

    private val migrations = PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*")
        .map { it.filename!! to it.inputStream.readAllBytes().decodeToString() }
        .toMap()

    private fun version(name: String) = Regex("^V(\\d+)__").find(name)?.groupValues?.get(1)?.toInt()

    @Test
    fun `new migrations follow the conventions for changing a table that is in use`() {
        val problems = migrations.filterKeys { (version(it) ?: 0) > LAST_UNCHECKED_VERSION && it.endsWith(".sql") }
            .flatMap { (name, sql) -> problems(name, sql, migrations["$name.conf"]) }

        assertThat(problems).isEmpty()
    }

    @Test
    fun `the first five migrations predate the conventions and would not pass them`() {
        val flagged = migrations.filterKeys { version(it) in 1..LAST_UNCHECKED_VERSION && it.endsWith(".sql") }
            .filter { (name, sql) -> problems(name, sql, null).isNotEmpty() }
            .keys

        assertThat(flagged).describedAs("so the check is known to see what it is for").contains(
            "V4__jdbc_schema.sql",
            "V5__link_disabling_and_listing.sql",
        )
    }

    @Test
    fun `an index must be built concurrently, in a migration that does not run in a transaction`() {
        assertThat(problems("V9__x.sql", "CREATE INDEX x_idx ON short_link (target_url);", null)).hasSize(1).first().asString()
            .contains("CONCURRENTLY")
        assertThat(problems("V9__x.sql", "CREATE UNIQUE INDEX x_idx ON short_link (target_url);", null)).hasSize(1)
        assertThat(problems("V9__x.sql", "CREATE INDEX CONCURRENTLY x_idx ON short_link (target_url);", "executeInTransaction=false")).isEmpty()
        assertThat(problems("V9__x.sql", "CREATE INDEX CONCURRENTLY x_idx ON short_link (target_url);", null)).hasSize(1).first().asString()
            .contains("executeInTransaction=false")
    }

    @Test
    fun `a check or a foreign key is added as not valid and validated by a later migration`() {
        assertThat(problems("V9__x.sql", "ALTER TABLE short_link ADD CONSTRAINT c CHECK (length(target_url) < 2048);", null)).hasSize(1).first()
            .asString().contains("NOT VALID")
        assertThat(problems("V9__x.sql", "ALTER TABLE a ADD CONSTRAINT f FOREIGN KEY (b) REFERENCES b (id);", null)).hasSize(1)
        assertThat(problems("V9__x.sql", "ALTER TABLE short_link ADD CONSTRAINT c CHECK (length(target_url) < 2048) NOT VALID;", null)).isEmpty()
        assertThat(problems("V10__x.sql", "ALTER TABLE short_link VALIDATE CONSTRAINT c;", null)).isEmpty()
        assertThat(problems("V9__x.sql", "ALTER TABLE t ADD CONSTRAINT c CHECK (a > 0) NOT VALID; ALTER TABLE t VALIDATE CONSTRAINT c;", null))
            .hasSize(1).first().asString().contains("own migration")
    }

    @Test
    fun `a unique key or primary key is attached to an index that already exists`() {
        assertThat(problems("V9__x.sql", "ALTER TABLE t ADD CONSTRAINT u UNIQUE (a);", null)).hasSize(1).first().asString().contains("USING INDEX")
        assertThat(problems("V9__x.sql", "ALTER TABLE t ADD PRIMARY KEY (a);", null)).hasSize(1)
        assertThat(problems("V9__x.sql", "ALTER TABLE t ADD CONSTRAINT u UNIQUE USING INDEX t_a_idx;", null)).isEmpty()
    }

    @Test
    fun `a column is not made not null, retyped or dropped, and a table is not dropped or renamed, without saying why it is safe`() {
        listOf(
            "ALTER TABLE t ALTER COLUMN a SET NOT NULL;",
            "ALTER TABLE t ALTER COLUMN a TYPE bigint;",
            "ALTER TABLE t DROP COLUMN a;",
            "ALTER TABLE t RENAME COLUMN a TO b;",
            "ALTER TABLE t RENAME TO u;",
            "DROP TABLE t;",
        ).forEach { assertThat(problems("V9__x.sql", it, null)).describedAs(it).hasSize(1) }
    }

    @Test
    fun `a migration can state that it is safe, and has to give the reason`() {
        assertThat(problems("V9__x.sql", "-- unsafe-ok: the table was created in V8 and is empty\nCREATE INDEX x ON t (a);", null)).isEmpty()
        assertThat(problems("V9__x.sql", "-- unsafe-ok:\nCREATE INDEX x ON t (a);", null)).isNotEmpty()
    }

    @Test
    fun `statements in comments and ordinary changes are left alone`() {
        assertThat(problems("V9__x.sql", "-- CREATE INDEX x ON t (a)\n/* DROP TABLE t */\nALTER TABLE t ADD COLUMN b text;", null)).isEmpty()
        assertThat(problems("V9__x.sql", "CREATE TABLE t (a int NOT NULL, CONSTRAINT c CHECK (a > 0));", null)).isEmpty()
    }

    private fun problems(name: String, sql: String, conf: String?): List<String> {
        if (Regex("--\\s*unsafe-ok:[ \\t]*\\S").containsMatchIn(sql)) return emptyList()
        val statements = sql.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("--[^\\n]*"), "")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }
        val found = mutableListOf<String>()
        fun flag(message: String) { found += "$name: $message" }
        fun matches(statement: String, pattern: String) = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(statement)

        statements.forEach { statement ->
            when {
                matches(statement, "^create\\s+(unique\\s+)?index\\s+(?!concurrently\\b)") ->
                    flag("CREATE INDEX blocks writes while it builds; use CREATE INDEX CONCURRENTLY (in its own migration without a transaction)")
                matches(statement, "\\badd\\s+(constraint\\s+\\S+\\s+)?(check|foreign\\s+key)\\b") && !matches(statement, "\\bnot\\s+valid\\b") ->
                    flag("adding a CHECK or FOREIGN KEY scans the table under an exclusive lock; add it NOT VALID and VALIDATE CONSTRAINT in a later migration")
                matches(statement, "\\badd\\s+(constraint\\s+\\S+\\s+)?(unique|primary\\s+key)\\b") && !matches(statement, "\\busing\\s+index\\b") ->
                    flag("adding a UNIQUE or PRIMARY KEY builds an index under an exclusive lock; create the index CONCURRENTLY and add the constraint USING INDEX")
                matches(statement, "\\bset\\s+not\\s+null\\b") ->
                    flag("SET NOT NULL scans the table under an exclusive lock; validate a CHECK (col IS NOT NULL) first, or state why it is safe")
                matches(statement, "\\balter\\s+column\\b.*\\btype\\b") ->
                    flag("changing a column type can rewrite the table under an exclusive lock; add a new column and move to it instead")
                matches(statement, "\\bdrop\\s+column\\b|\\brename\\b|^drop\\s+table\\b") ->
                    flag("dropping or renaming is not compatible with the previous version, which keeps running during a rolling update; do it a release later")
            }
        }
        if (statements.any { matches(it, "\\bvalidate\\s+constraint\\b") } && statements.any { matches(it, "\\badd\\s+(constraint|check|foreign|unique|primary)") }) {
            flag("VALIDATE CONSTRAINT must be in its own migration, or the lock taken by the statement that added the constraint is held through it")
        }
        if (statements.any { matches(it, "^create\\s+(unique\\s+)?index\\s+concurrently\\b") } && conf?.contains("executeInTransaction=false") != true) {
            flag("CREATE INDEX CONCURRENTLY cannot run in a transaction; add a $name.conf file containing executeInTransaction=false")
        }
        return found
    }

    private companion object {
        const val LAST_UNCHECKED_VERSION = 5
    }
}
