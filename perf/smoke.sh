#!/usr/bin/env bash
# Exercises every endpoint of a running application with DPoP-bound tokens from the local Keycloak, as the demo, other
# and admin clients, and checks the status each should answer. Meant for a native image, where what works on the JVM
# can still fail for want of reflection metadata.
#   perf/smoke.sh [BASE_URL]
set -uo pipefail
BASE=${1:-http://localhost:8080}
MGMT=${MGMT:-http://localhost:8081}
HERE=$(cd "$(dirname "$0")/.." && pwd)
KEYS=$HERE/deploy/keycloak/dev-keys
fail=0

call() { java "$HERE/deploy/keycloak/DpopClient.java" call "$KEYS/$1.jwk.json" "$1" "${@:2}"; }
status() { call "$@" | head -1; }
check() { # name expected actual
  if [ "$2" = "$3" ]; then printf 'ok    %s\n' "$1"; else printf 'FAIL  %s (expected %s, got %s)\n' "$1" "$2" "$3"; fail=$((fail + 1)); fi
}
field() { sed -n 's/.*"'"$1"'":"\([^"]*\)".*/\1/p' | head -1; }

generated=$(call demo-client POST "$BASE/api/short-links" '{"targetUrl":"https://example.com/smoke"}')
check "create with a generated code" 201 "$(echo "$generated" | head -1)"
code=$(echo "$generated" | field shortCode)
custom="smoke-$RANDOM"
check "create with a custom code" 201 "$(status demo-client POST "$BASE/api/short-links" "{\"targetUrl\":\"https://example.com/custom\",\"customCode\":\"$custom\"}")"
check "custom code taken" 409 "$(status demo-client POST "$BASE/api/short-links" "{\"targetUrl\":\"https://example.com/custom\",\"customCode\":\"$custom\"}")"
check "invalid url" 400 "$(status demo-client POST "$BASE/api/short-links" '{"targetUrl":"not a url"}')"
check "missing url" 400 "$(status demo-client POST "$BASE/api/short-links" '{}')"
check "get own link" 200 "$(status demo-client GET "$BASE/api/short-links/$code")"
check "another client's link looks not found" 404 "$(status other-client GET "$BASE/api/short-links/$code")"
check "admin reads any link" 200 "$(status admin-client GET "$BASE/api/short-links/$code")"
check "list own links" 200 "$(status demo-client GET "$BASE/api/short-links?size=1&sort=shortCode,asc")"
check "list with a sort that is not allowed" 400 "$(status demo-client GET "$BASE/api/short-links?sort=targetUrl")"
check "no-scope client cannot create" 403 "$(status no-scope-client POST "$BASE/api/short-links" '{"targetUrl":"https://example.com/x"}')"
check "redirect is public" 302 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/$code")"
check "unknown code" 404 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/zzzzzzz")"
check "another client cannot disable" 404 "$(status other-client DELETE "$BASE/api/short-links/$code")"
check "owner disables" 204 "$(status demo-client DELETE "$BASE/api/short-links/$code")"
check "disabled link answers gone" 410 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/$code")"
check "bearer scheme is refused" 401 "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(java "$HERE/deploy/keycloak/DpopClient.java" token "$KEYS/demo-client.jwk.json" demo-client)" "$BASE/api/short-links")"
check "no credentials" 401 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/short-links")"
check "protected resource metadata" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/.well-known/oauth-protected-resource")"
check "readiness" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$MGMT/actuator/health/readiness")"
check "request metrics are exported" 1 "$(curl -s "$MGMT/actuator/prometheus" | grep -c '^http_server_requests_seconds_bucket' | sed 's/^[1-9][0-9]*$/1/')"

[ "$fail" = 0 ] && echo "all checks passed" || { echo "$fail checks failed"; exit 1; }
