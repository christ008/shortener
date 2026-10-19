-- One-time, idempotent setup of a Shortener database, run by a superuser (or the database owner with createrole)
-- connected to the target database:  psql -d shortener -f bootstrap.sql
--
-- It separates what changes the schema from what serves traffic:
--   shortener_migrator  owns the tables and runs the Flyway migrations (the init container, never the app container)
--   shortener_app       serves requests: it can read and insert links and mark them disabled, nothing else
--   shortener_exporter  reads statistics for postgres_exporter (pg_monitor); it cannot read the data
-- It creates the roles without passwords, because those belong to the platform (a Docker secret, Vault); set
-- them with alter role ... password. The privileges on short_link itself are granted by the Flyway migration that
-- creates or changes it, so they stay next to the schema they describe.

do
$$
declare
    r text;
begin
    foreach r in array array ['shortener_migrator', 'shortener_app', 'shortener_exporter']
        loop
            if not exists (select 1 from pg_roles where rolname = r) then
                execute format('create role %I login nosuperuser nocreatedb nocreaterole noreplication nobypassrls', r);
            end if;
        end loop;
end
$$;

grant pg_monitor to shortener_exporter;

-- The owner of analytics_retention() (migration V9, ADR 0043). It cannot log in, and holds only what that function needs, which V9
-- grants next to the tables: nothing on short_link, and no delete on the monthly counts. The migrator is a member so that the
-- migration can hand the function to it. A database whose V9 ran before this role existed keeps the function with the migrator.
do
$$
begin
    if not exists (select 1 from pg_roles where rolname = 'shortener_retention') then
        create role shortener_retention nologin nosuperuser nocreatedb nocreaterole noreplication nobypassrls;
    end if;
end
$$;
grant shortener_retention to shortener_migrator;
-- create on public is what a new owner of a function must hold. The role cannot log in, and the only code that runs as it is that
-- function, which builds no statement from text.
grant usage, create on schema public to shortener_retention;

-- Only these roles may connect, and nobody may create objects in public except the migrator.
do
$$
begin
    execute format('revoke all on database %I from public', current_database());
    execute format('grant connect on database %I to shortener_migrator, shortener_app, shortener_exporter', current_database());
end
$$;
revoke create on schema public from public;
grant usage on schema public to shortener_app, shortener_exporter;
grant usage, create on schema public to shortener_migrator;

-- A database that predates this script has tables owned by whoever created them; the migrator must own them to
-- alter them. A no-op on a fresh database and on every later run.
do
$$
declare
    t text;
begin
    for t in select format('%I.%I', schemaname, tablename)
             from pg_tables
             where schemaname = 'public' and tableowner <> 'shortener_migrator'
        loop
            execute format('alter table %s owner to shortener_migrator', t);
        end loop;
end
$$;

-- Limits that no application setting can lift. They apply at login, so they also hold behind a connection pooler.
-- A request that needs more than 5 s of database time is a bug, not a slow query to wait for; a held lock or an
-- abandoned transaction must not pin one of an instance's ten connections.
alter role shortener_app set statement_timeout = '5s';
alter role shortener_app set lock_timeout = '2s';
alter role shortener_app set idle_in_transaction_session_timeout = '10s';
-- A migration may run for minutes, but it must not queue behind a long query while blocking every later one.
alter role shortener_migrator set lock_timeout = '10s';
alter role shortener_migrator set statement_timeout = 0;
alter role shortener_migrator set idle_in_transaction_session_timeout = '60s';

-- Per-statement statistics, the first thing to look at when tuning. The server must also preload the library
-- (shared_preload_libraries = 'pg_stat_statements'); until it does the view exists but cannot be read.
do
$$
begin
    if exists (select 1 from pg_available_extensions where name = 'pg_stat_statements') then
        create extension if not exists pg_stat_statements;
    end if;
end
$$;
