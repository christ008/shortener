-- Least privilege for the role that serves requests. deploy/postgres/bootstrap.sql creates it; where it does not exist
-- (a developer's own database, a throwaway test container) there is nothing to grant. The migrations run as another
-- role, shortener_migrator, so what the application may do is stated here, next to the schema, and nowhere else:
-- read and insert links, mark them disabled, and read the Flyway history (Flyway checks it on every start, and finds
-- nothing to apply). Not DELETE, not TRUNCATE, not changing a target or a code, and no DDL at all.
DO
$$
    BEGIN
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'shortener_app') THEN
            REVOKE ALL ON TABLE short_link FROM shortener_app;
            GRANT SELECT, INSERT ON TABLE short_link TO shortener_app;
            GRANT UPDATE (disabled_at, disabled_by) ON TABLE short_link TO shortener_app;
            GRANT SELECT ON TABLE flyway_schema_history TO shortener_app;
        END IF;
    END
$$;
