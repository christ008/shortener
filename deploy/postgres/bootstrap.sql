-- One-time, idempotent setup of a Shortener database, run by a superuser (or the database owner with CREATEROLE)
-- connected to the target database:  psql -d shortener -f bootstrap.sql
--
-- It separates what changes the schema from what serves traffic:
--   shortener_migrator  owns the tables and runs the Flyway migrations (the init container, never the app container)
--   shortener_app       serves requests: it can read and insert links and mark them disabled, nothing else
--   shortener_exporter  reads statistics for postgres_exporter (pg_monitor); it cannot read the data
-- It creates the roles without passwords, because those belong to the platform (a Docker secret, Vault); set
-- them with ALTER ROLE ... PASSWORD. The privileges on short_link itself are granted by the Flyway migration that
-- creates or changes it, so they stay next to the schema they describe.

DO
$$
DECLARE
    r text;
BEGIN
    FOREACH r IN ARRAY ARRAY ['shortener_migrator', 'shortener_app', 'shortener_exporter']
        LOOP
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
                EXECUTE format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', r);
            END IF;
        END LOOP;
END
$$;

GRANT pg_monitor TO shortener_exporter;

-- Only these roles may connect, and nobody may create objects in public except the migrator.
DO
$$
BEGIN
    EXECUTE format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database());
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO shortener_migrator, shortener_app, shortener_exporter', current_database());
END
$$;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO shortener_app, shortener_exporter;
GRANT USAGE, CREATE ON SCHEMA public TO shortener_migrator;

-- A database that predates this script has tables owned by whoever created them; the migrator must own them to
-- alter them. A no-op on a fresh database and on every later run.
DO
$$
DECLARE
    t text;
BEGIN
    FOR t IN SELECT format('%I.%I', schemaname, tablename)
             FROM pg_tables
             WHERE schemaname = 'public' AND tableowner <> 'shortener_migrator'
        LOOP
            EXECUTE format('ALTER TABLE %s OWNER TO shortener_migrator', t);
        END LOOP;
END
$$;

-- Limits that no application setting can lift. They apply at login, so they also hold behind a connection pooler.
-- A request that needs more than 5 s of database time is a bug, not a slow query to wait for; a held lock or an
-- abandoned transaction must not pin one of an instance's ten connections.
ALTER ROLE shortener_app SET statement_timeout = '5s';
ALTER ROLE shortener_app SET lock_timeout = '2s';
ALTER ROLE shortener_app SET idle_in_transaction_session_timeout = '10s';
-- A migration may run for minutes, but it must not queue behind a long query while blocking every later one.
ALTER ROLE shortener_migrator SET lock_timeout = '10s';
ALTER ROLE shortener_migrator SET statement_timeout = 0;
ALTER ROLE shortener_migrator SET idle_in_transaction_session_timeout = '60s';

-- Per-statement statistics, the first thing to look at when tuning. The server must also preload the library
-- (shared_preload_libraries = 'pg_stat_statements'); until it does the view exists but cannot be read.
DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_stat_statements') THEN
        CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
    END IF;
END
$$;
