# Observability

What the service reports about itself, what it looks like, and what to alert on. Running it locally:
`docker compose --profile observability up -d` starts Prometheus, Grafana (<http://localhost:3000>, no login), Tempo and the
Postgres exporter, and `./gradlew bootRun` uses the `dev` profile, which samples every trace. The production stack has
its own overlay, see [DEPLOY.md](DEPLOY.md#operate).

- [At a glance](#at-a-glance)
- [Dashboard](#dashboard)
- [Traces](#traces)
- [Metrics](#metrics)
- [Alerts](#alerts)
- [Health, logs and the database](#health-logs-and-the-database)
- [Not done](#not-done)

## At a glance

| Signal | Where | Notes |
|---|---|---|
| Metrics | `/actuator/prometheus`, management port 8081 | internal, never proxied by the edge |
| Dashboard | `deploy/observability/grafana/dashboards/shortener.json` | twelve panels |
| Traces | OTLP over HTTP to Tempo | sampling 0 by default, 100% under `dev`, 5% under `production` |
| Logs | JSON on stdout (ECS) under `production` | trace and span ids included |
| Health | `/actuator/health/liveness` and `/readiness`, management port | readiness does not include the database |
| Alerts | `deploy/observability/alerts.yml` | five rules, unit-tested with promtool |

## Dashboard

The shipped dashboard during a run of 1,500 requests a second, 1% of them creates, with the JVM limited to two cores and
a 512 MB memory ceiling. The redirect cache answers about 99% of redirects (the hit ratio panel), so the connection pool stays idle.

![Grafana dashboard](images/dashboard.png)

| Panel | Answers | Main series |
|---|---|---|
| Requests per second by status | how much traffic, and how it ends | `http_server_requests_seconds_count` by `status` |
| Redirect latency | how fast `GET /{shortCode}` is (p50, p95, p99) | `http_server_requests_seconds_bucket`, `uri="/{shortCode}"` |
| Create latency | how fast `POST /api/short-links` is | the same, `uri="/api/short-links"` |
| Rejected and failed requests | what is refused: 401, 403, 429, 5xx | `http_server_requests_seconds_count` by `status` |
| Connection pool | active, idle, pending and maximum connections | `hikaricp_connections_*` |
| Connection acquire time and timeouts | whether requests wait for the database | `hikaricp_connections_acquire_seconds_max`, `hikaricp_connections_timeout_total` |
| Heap used and max | memory pressure | `jvm_memory_used_bytes`, `jvm_memory_max_bytes` |
| GC pause time per second | cost of garbage collection | `jvm_gc_pause_seconds_sum` |
| CPU usage | process and system CPU | `process_cpu_usage`, `system_cpu_usage` |
| Live threads | live and peak threads | `jvm_threads_live_threads`, `jvm_threads_peak_threads` |
| Redirect cache hit ratio | the share of redirects answered from memory | `cache_gets_total` by `result` |
| Redirect cache size and evictions | whether the cache is full | `cache_size`, `cache_evictions_total` |

- A panel stays empty until its event happens, because Prometheus has no series for something that has not occurred.
- The native image has no JVM garbage collector beans, so its GC panel is empty and its heap maximum reads zero.
- Route templates, not codes, label the request series, so short codes never become label values (tested).

## Traces

A create request in Tempo: the HTTP span and, under it, the `shortlink.insert` span for the database write.

![A create request as a trace in Tempo](images/trace.png)

| Span | Means |
|---|---|
| `http <method> <route>` | one per request, named by route (`http get /{shortCode}`), joined to the caller's trace by `traceparent` |
| `shortlink.load` | a redirect that missed the cache and read the database. A cached redirect has no such span |
| `shortlink.insert` | one attempt to store a link. A code collision shows as a second insert |

- `shortlink.load` and `shortlink.insert` are also timers: `shortlink_load_seconds` and `shortlink_insert_seconds`.
- Trace and span ids are in the logs. Spring Security's per-filter observations are off, which would add a span per filter.
- The exporter is always built in and its endpoint has a default in `application.yaml`. A native image decides at build
  time which beans exist, so removing the endpoint, or supplying it only at run time, compiles the exporter out
  silently. Environment variables still override the value at run time.
- Export of logs and metrics over OTLP is off.

## Metrics

| Series | Type | Use |
|---|---|---|
| `http_server_requests_seconds` | histogram | rate, errors and latency by route and status |
| `hikaricp_connections_active`, `_idle`, `_pending`, `_max` | gauge | pool use |
| `hikaricp_connections_acquire_seconds_max` | gauge | the longest wait for a connection |
| `hikaricp_connections_timeout_total` | counter | requests that gave up waiting for a connection |
| `cache_gets_total{cache="shortlink.redirect"}` | counter | hits and misses of the redirect cache |
| `cache_size`, `cache_evictions_total` | gauge, counter | cache fill |
| `shortlink_redirect_cache_stale_total` | counter | redirects served from an expired entry while the database was down. Should be zero |
| `shortlink_load_seconds`, `shortlink_insert_seconds` | timer | the database read of a redirect, and each insert attempt |
| `jvm_*`, `process_*` | | memory, GC, threads and CPU of the JVM |
| `pg_*` | | the database, from the Postgres exporter |

## Alerts

`deploy/observability/alerts.yml`, on what the application sees of its database and its cache. The cache answers about 99%
of redirects, so the pool is idle in normal operation: any of these means the cache is not absorbing the load, or the
database is slow or gone.

| Alert | Fires when | For | Severity |
|---|---|---|---|
| `ShortenerDatabasePoolExhausted` | requests are waiting for a connection | 2 m | warning |
| `ShortenerDatabaseConnectionTimeouts` | requests gave up waiting for a connection, or the database could not be reached | 1 m | warning |
| `ShortenerDatabaseSlowToConnect` | the slowest wait for a connection exceeds 500 ms | 10 m | warning |
| `ShortenerStorageUnavailable` | more than 1% of requests are answered `503` | 5 m | critical |
| `ShortenerServingStaleRedirects` | redirects are served from an expired entry because the database cannot be reached | 2 m | warning |

- Each rule has a unit test with simulated series, in `deploy/observability/alerts.test.yml`. CI runs them with promtool:

  ```bash
  docker run --rm -v "$PWD/deploy/observability:/rules:ro" -w /rules --entrypoint promtool \
    prom/prometheus:v3.5.0 test rules alerts.test.yml
  ```
- Prometheus evaluates them and shows them in its interface. Sending them somewhere needs an Alertmanager, see
  `deploy/observability/prometheus.stack.yml`.
- The database server's own alerts (connections against `max_connections`, replication, backup age, transaction age)
  depend on how Postgres is run, and are not here.

## Health, logs and the database

| What | Behaviour |
|---|---|
| Liveness | the process is up |
| Readiness | the application's own state only. It excludes the database: every instance shares one, so removing them all from rotation gains nothing, and the health check that restarts unhealthy containers would restart all of them during an outage. An instance answers what it can and gives `503` with `Retry-After` for the rest |
| Logs | structured ECS JSON under the `production` profile, plain text otherwise |
| Slow statements | `deploy/postgres/diagnostics.conf` logs statements over 250 ms with their plan, lock waits, sorts that spill to disk and slow autovacuums |
| Database metrics | `postgres_exporter` as `shortener_exporter`, limited to 25 statements and no query text |
| When it is slow | `perf/pg-diagnostics.sql`: connections by application and state, what waits for a lock, the costliest statements, cache hit ratio, dead rows, unused indexes and distance from transaction ID wraparound |

Every connection reports `shortener-<hostname>` as its application name, so `pg_stat_activity` says who holds what.

## Not done

- Metrics from the nginx edge.
- A Grafana dashboard for Postgres. The exporter's series are there to build one.
- Anything that sends the alerts Prometheus fires.
