-- What to look at when the database is slow, or before deciding what to tune. Read-only, and it works as a member of
-- pg_monitor (the shortener_exporter role) as well as a superuser:
--   psql -h HOST -U shortener_exporter -d shortener -f perf/pg-diagnostics.sql
-- The statement statistics need pg_stat_statements (deploy/postgres/diagnostics.conf loads it); the rest does not.
-- Counters accumulate since the last reset, so compare two runs under the same load instead of reading one: reset the
-- statements with  SELECT pg_stat_statements_reset();  and the rest with  SELECT pg_stat_reset();  before a test.

\pset null '-'
\pset footer off
\x auto

\echo
\echo '== Connections against the limit (an instance holds up to 10; instances x 10 must stay well below max_connections)'
SELECT count(*)                                                                         AS used,
       current_setting('max_connections')::int                                          AS max_connections,
       round(100.0 * count(*) / current_setting('max_connections')::int, 1)             AS pct_used
FROM pg_stat_activity;

\echo
\echo '== Who holds them: by application (shortener-<hostname>), role and state. Many "idle in transaction" is a leak.'
SELECT application_name, usename, state, count(*) AS connections
FROM pg_stat_activity
WHERE backend_type = 'client backend'
GROUP BY 1, 2, 3
ORDER BY connections DESC;

\echo
\echo '== Running now, longest first (the application role is cut off at 5 s)'
SELECT pid, now() - query_start AS running, state, wait_event_type, wait_event, application_name,
       left(regexp_replace(query, '\s+', ' ', 'g'), 100) AS query
FROM pg_stat_activity
WHERE state <> 'idle' AND pid <> pg_backend_pid() AND backend_type = 'client backend'
ORDER BY query_start
LIMIT 10;

\echo
\echo '== Waiting for a lock, and who blocks it'
SELECT a.pid AS waiting, pg_blocking_pids(a.pid) AS blocked_by, now() - a.query_start AS for_how_long,
       a.application_name, left(regexp_replace(a.query, '\s+', ' ', 'g'), 80) AS query
FROM pg_stat_activity a
WHERE cardinality(pg_blocking_pids(a.pid)) > 0;

\echo
\echo '== This database: cache hit ratio, rollbacks, deadlocks, sorts that spilled to disk'
SELECT datname,
       round(100.0 * blks_hit / nullif(blks_hit + blks_read, 0), 2) AS cache_hit_pct,
       xact_commit, xact_rollback, deadlocks, temp_files, pg_size_pretty(temp_bytes) AS temp_bytes, stats_reset
FROM pg_stat_database
WHERE datname = current_database();

SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements') AS has_statements \gset
\if :has_statements
\echo
\echo '== Statements that cost the most in total (calls x time): where tuning pays most'
SELECT round(total_exec_time::numeric / 1000, 1) AS total_s, calls, round(mean_exec_time::numeric, 2) AS mean_ms,
       round(stddev_exec_time::numeric, 2) AS stddev_ms, rows,
       round(100.0 * shared_blks_hit / nullif(shared_blks_hit + shared_blks_read, 0), 1) AS hit_pct,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY total_exec_time DESC
LIMIT 15;

\echo
\echo '== Slowest on average, with at least 10 calls: the ones a user feels'
SELECT round(mean_exec_time::numeric, 2) AS mean_ms, round(max_exec_time::numeric, 1) AS max_ms, calls, rows,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90) AS query
FROM pg_stat_statements
WHERE calls >= 10 AND dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY mean_exec_time DESC
LIMIT 10;

\echo
\echo '== Reading the most from disk, and spilling sorts to temp files (the first wants an index or memory, the second work_mem)'
SELECT shared_blks_read, temp_blks_written, calls, round(mean_exec_time::numeric, 2) AS mean_ms,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
ORDER BY shared_blks_read + temp_blks_written DESC
LIMIT 10;
\else
\echo
\echo '== pg_stat_statements is not installed in this database: CREATE EXTENSION pg_stat_statements (deploy/postgres/bootstrap.sql does)'
\endif

\echo
\echo '== Tables: size, dead rows (autovacuum behind?), and sequential scans against index scans'
SELECT relname, pg_size_pretty(pg_total_relation_size(relid)) AS total_size, n_live_tup, n_dead_tup,
       round(100.0 * n_dead_tup / nullif(n_live_tup + n_dead_tup, 0), 1) AS dead_pct,
       seq_scan, idx_scan, last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables
ORDER BY pg_total_relation_size(relid) DESC;

\echo
\echo '== Indexes: size and use. One never scanned since the statistics were reset costs every write and buys nothing.'
SELECT s.relname AS table_name, s.indexrelname AS index_name, pg_size_pretty(pg_relation_size(s.indexrelid)) AS size,
       s.idx_scan, i.indisunique AS "unique", i.indisprimary AS "primary"
FROM pg_stat_user_indexes s
         JOIN pg_index i ON i.indexrelid = s.indexrelid
ORDER BY s.idx_scan, pg_relation_size(s.indexrelid) DESC;

\echo
\echo '== Transaction ID age: how far each database is from wraparound (autovacuum freezes at 200 million; trouble at 2 billion)'
SELECT datname, age(datfrozenxid) AS xid_age, round(100.0 * age(datfrozenxid) / 2147483647, 2) AS pct_to_wraparound
FROM pg_database
WHERE datallowconn
ORDER BY xid_age DESC;

\echo
\echo '== Settings that matter most here'
SELECT name, setting, unit, source
FROM pg_settings
WHERE name IN ('max_connections', 'shared_buffers', 'effective_cache_size', 'work_mem', 'maintenance_work_mem',
               'random_page_cost', 'effective_io_concurrency', 'max_wal_size', 'checkpoint_timeout',
               'autovacuum_vacuum_scale_factor', 'autovacuum_max_workers', 'track_io_timing',
               'shared_preload_libraries', 'log_min_duration_statement')
ORDER BY name;
