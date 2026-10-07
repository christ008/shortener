#!/bin/sh
# Loads COUNT generated codes (tools/run Dataset) into short_link of the compose Postgres, for the benchmarks that read links the
# redirect cache has not seen. It adds to what is there: perf/bench.sh truncates the table before it warms up, so it is told to keep it.
# It keeps the codes in FILE (DATASET_FILE, default perf/data/codes-COUNT-seedSEED.txt): 8 bytes a code, which is what perf/k6/mixed.js reads by
# position, and what perf/bench.sh is given as DATASET_FILE.
#   [SEED=1] [DATASET_FILE=FILE] [PG_CONTAINER=NAME] perf/load-dataset.sh COUNT          for example: perf/load-dataset.sh 10M
# Needs the schema (the application migrates it) and Postgres running: the compose service, or PG_CONTAINER of your own.
set -eu

COUNT=${1:?usage: perf/load-dataset.sh COUNT}
SEED=${SEED:-1}
ROOT=$(cd "$(dirname "$0")/.." && pwd)
PG_CONTAINER=${PG_CONTAINER:-$(docker compose -f "$ROOT/compose.yaml" ps -q postgres)}
[ -n "$PG_CONTAINER" ] || { echo "no Postgres container: start the compose service, or set PG_CONTAINER" >&2; exit 1; }
FILE=${DATASET_FILE:-$ROOT/perf/data/codes-$COUNT-seed$SEED.txt}
mkdir -p "$(dirname "$FILE")"
say() { printf '\n\033[1m[%s] %s\033[0m\n' "$(date +%T)" "$*"; }
psql() { docker exec -i "$PG_CONTAINER" psql -q -v ON_ERROR_STOP=1 -U myuser -d mydatabase "$@"; }

say "generating $COUNT codes, seed $SEED, into $FILE"
"$ROOT/tools/run" Dataset "$COUNT" --seed "$SEED" --out "$FILE"
LINES=$(wc -l <"$FILE")

say "short_link before: $(psql -At -c 'SELECT count(*) FROM short_link') rows"
say "copying $LINES rows into $PG_CONTAINER, every index maintained as the application's inserts do"
start=$(date +%s%N)
awk '{ printf "%s\thttps://example.com/dataset/%s\n", $1, $1 }' "$FILE" | psql -c 'COPY short_link (short_code, target_url) FROM STDIN'
elapsed_ms=$((($(date +%s%N) - start) / 1000000))
say "copied in $((elapsed_ms / 1000)).$((elapsed_ms % 1000 / 100)) s: $((LINES * 1000 / (elapsed_ms + 1))) rows/s"

say "analyzing"
psql -c 'ANALYZE short_link'
psql -c "SELECT 'rows' AS what, count(*)::text AS value FROM short_link
         UNION ALL SELECT 'table', pg_size_pretty(pg_table_size('short_link'))
         UNION ALL SELECT 'indexes', pg_size_pretty(pg_indexes_size('short_link'))
         UNION ALL SELECT 'primary key', pg_size_pretty(pg_relation_size('short_link_pkey'))"

say "a read by primary key, as a redirect miss does it"
CODE=$(sed -n "$((LINES / 2))p" "$FILE")
psql -c "EXPLAIN (ANALYZE, BUFFERS, COSTS OFF, TIMING ON) SELECT target_url FROM short_link WHERE short_code = '$CODE'"

say "to read them under load: DATASET_FILE=$FILE perf/bench.sh VARIANT IMAGE OUT_DIR"
