#!/bin/sh
# Removes what k6 puts into a summary that must not be kept: the DPoP proof key and the access token that `setup()` of perf/k6/mixed.js returns,
# which `--summary-export` copies under "setup_data". The code list stays, because it is neither. Used by perf/bench.sh and perf/profile.sh
# on every summary they write, and it fails when it cannot, so that no run leaves one behind.
#   perf/scrub-k6-summary.sh FILE...
# Needs jq. The file is replaced, not written in place: k6 runs as another user, and the directory is what must be writable. Its indentation
# is jq's, and its content is the same without those two members.
set -eu

command -v jq >/dev/null 2>&1 || { echo "perf/scrub-k6-summary.sh needs jq: k6 summaries hold a DPoP key and a token until it removes them" >&2; exit 1; }
[ $# -gt 0 ] || { echo "usage: perf/scrub-k6-summary.sh FILE..." >&2; exit 2; }

for file in "$@"; do
  [ -s "$file" ] || continue
  scrubbed=$(mktemp "$file.XXXXXX")
  if jq 'del(.setup_data.dpopJwk, .setup_data.token)' "$file" >"$scrubbed" && chmod 644 "$scrubbed" && mv -f "$scrubbed" "$file"; then
    :
  else
    rm -f "$scrubbed" "$file"
    echo "$file: could not be cleaned of the key and token (not valid JSON, or not replaceable), so it was removed" >&2
    exit 1
  fi
done
