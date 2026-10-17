-- What to look at when the database is slow, or before deciding what to tune. Read-only, and it works as a member of
-- pg_monitor (the shortener_exporter role) as well as a superuser:
--   psql -h HOST -U shortener_exporter -d shortener -f perf/pg-diagnostics.sql
-- The statement statistics need pg_stat_statements (deploy/postgres/diagnostics.conf loads it); the rest does not.
-- Counters accumulate since the last reset, so compare two runs under the same load instead of reading one: reset the
-- statements with  select pg_stat_statements_reset();  and the rest with  select pg_stat_reset();  before a test.

\pset null '-'
\pset footer off
\x auto

\echo
\echo '== Connections against the limit (an instance holds up to 10; instances x 10 must stay well below max_connections)'
select count(*)                                                                         as used,
       current_setting('max_connections')::int                                          as max_connections,
       round(100.0 * count(*) / current_setting('max_connections')::int, 1)             as pct_used
from pg_stat_activity;

\echo
\echo '== Who holds them: by application (shortener-<hostname>), role and state. Many "idle in transaction" is a leak.'
select application_name, usename, state, count(*) as connections
from pg_stat_activity
where backend_type = 'client backend'
group by 1, 2, 3
order by connections desc;

\echo
\echo '== Running now, longest first (the application role is cut off at 5 s)'
select pid, now() - query_start as running, state, wait_event_type, wait_event, application_name,
       left(regexp_replace(query, '\s+', ' ', 'g'), 100) as query
from pg_stat_activity
where state <> 'idle' and pid <> pg_backend_pid() and backend_type = 'client backend'
order by query_start
limit 10;

\echo
\echo '== Waiting for a lock, and who blocks it'
select a.pid as waiting, pg_blocking_pids(a.pid) as blocked_by, now() - a.query_start as for_how_long,
       a.application_name, left(regexp_replace(a.query, '\s+', ' ', 'g'), 80) as query
from pg_stat_activity a
where cardinality(pg_blocking_pids(a.pid)) > 0;

\echo
\echo '== This database: cache hit ratio, rollbacks, deadlocks, sorts that spilled to disk'
select datname,
       round(100.0 * blks_hit / nullif(blks_hit + blks_read, 0), 2) as cache_hit_pct,
       xact_commit, xact_rollback, deadlocks, temp_files, pg_size_pretty(temp_bytes) as temp_bytes, stats_reset
from pg_stat_database
where datname = current_database();

select exists (select 1 from pg_extension where extname = 'pg_stat_statements') as has_statements \gset
\if :has_statements
\echo
\echo '== Statements that cost the most in total (calls x time): where tuning pays most'
select round(total_exec_time::numeric / 1000, 1) as total_s, calls, round(mean_exec_time::numeric, 2) as mean_ms,
       round(stddev_exec_time::numeric, 2) as stddev_ms, rows,
       round(100.0 * shared_blks_hit / nullif(shared_blks_hit + shared_blks_read, 0), 1) as hit_pct,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90) as query
from pg_stat_statements
where dbid = (select oid from pg_database where datname = current_database())
order by total_exec_time desc
limit 15;

\echo
\echo '== Slowest on average, with at least 10 calls: the ones a user feels'
select round(mean_exec_time::numeric, 2) as mean_ms, round(max_exec_time::numeric, 1) as max_ms, calls, rows,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90) as query
from pg_stat_statements
where calls >= 10 and dbid = (select oid from pg_database where datname = current_database())
order by mean_exec_time desc
limit 10;

\echo
\echo '== Reading the most from disk, and spilling sorts to temp files (the first wants an index or memory, the second work_mem)'
select shared_blks_read, temp_blks_written, calls, round(mean_exec_time::numeric, 2) as mean_ms,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90) as query
from pg_stat_statements
where dbid = (select oid from pg_database where datname = current_database())
order by shared_blks_read + temp_blks_written desc
limit 10;
\else
\echo
\echo '== pg_stat_statements is not installed in this database: create extension pg_stat_statements (deploy/postgres/bootstrap.sql does)'
\endif

\echo
\echo '== Tables: size, dead rows (autovacuum behind?), and sequential scans against index scans'
select relname, pg_size_pretty(pg_total_relation_size(relid)) as total_size, n_live_tup, n_dead_tup,
       round(100.0 * n_dead_tup / nullif(n_live_tup + n_dead_tup, 0), 1) as dead_pct,
       seq_scan, idx_scan, last_autovacuum, last_autoanalyze
from pg_stat_user_tables
order by pg_total_relation_size(relid) desc;

\echo
\echo '== Indexes: size and use. One never scanned since the statistics were reset costs every write and buys nothing.'
select s.relname as table_name, s.indexrelname as index_name, pg_size_pretty(pg_relation_size(s.indexrelid)) as size,
       s.idx_scan, i.indisunique as "unique", i.indisprimary as "primary"
from pg_stat_user_indexes s
         join pg_index i on i.indexrelid = s.indexrelid
order by s.idx_scan, pg_relation_size(s.indexrelid) desc;

\echo
\echo '== Transaction ID age: how far each database is from wraparound (autovacuum freezes at 200 million; trouble at 2 billion)'
select datname, age(datfrozenxid) as xid_age, round(100.0 * age(datfrozenxid) / 2147483647, 2) as pct_to_wraparound
from pg_database
where datallowconn
order by xid_age desc;

\echo
\echo '== Settings that matter most here'
select name, setting, unit, source
from pg_settings
where name in ('max_connections', 'shared_buffers', 'effective_cache_size', 'work_mem', 'maintenance_work_mem',
               'random_page_cost', 'effective_io_concurrency', 'max_wal_size', 'checkpoint_timeout',
               'autovacuum_vacuum_scale_factor', 'autovacuum_max_workers', 'track_io_timing',
               'shared_preload_libraries', 'log_min_duration_statement')
order by name;
