#!/bin/sh
# Restores the latest backup into an empty directory, recovers it, prints how many links it has and when the newest was created,
# and removes the copy. Run it with `deploy/postgres/backup restore-test`.
# The count is that of the archived WAL, at most archive_timeout (5 minutes) behind the primary.
# Exit status: non-zero when the restore or the recovery fails.
set -eu
data=/tmp/restored
mkdir -p "$data"
chmod 0700 "$data"
chown postgres:postgres "$data"

echo "restore-test: restoring the latest backup" >&2
gosu postgres pgbackrest --stanza=shortener --pg1-path="$data" --log-level-console=warn --log-level-file=off restore

echo "restore-test: starting the copy and replaying the archived WAL" >&2
gosu postgres pg_ctl -D "$data" -w -t 300 -l /tmp/restored.log \
  -o "-c archive_mode=off -c listen_addresses='' -c unix_socket_directories=/tmp -c port=5499" start >/dev/null

# A recovering server accepts connections only when it is done, and then promotes itself.
query() { gosu postgres psql -h /tmp -p 5499 -U postgres -d shortener -tAc "$1"; }
count=$(query 'select count(*) from short_link')
newest=$(query "select coalesce(max(created_at)::text, 'no links') from short_link")
gosu postgres pg_ctl -D "$data" -m fast -w stop >/dev/null
echo "restore-test: ok, the copy has $count links, the newest created at $newest"
