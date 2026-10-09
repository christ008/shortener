#!/bin/sh
# Starts a built image of the application as the container "app", on the host network and against the Postgres and Keycloak of
# compose.yaml, and returns when the application is ready. For CI and for trying an image by hand.
#
# - The image runs under the production profile, so it gets what production requires: every target host accepted, and a random
#   DPoP nonce secret.
# - Waits up to 120 seconds for readiness, and fails at once when the container has stopped, printing the end of its log.
#
#   perf/start-image.sh IMAGE
set -eu
image=${1:?usage: perf/start-image.sh IMAGE}
cd "$(dirname "$0")/.."

set -a
# shellcheck disable=SC1091
. ./.env
set +a
echo "start-image: starting $image"
port=$(docker compose port postgres 5432 | cut -d: -f2)
docker run -d --name app --network host --memory 512m \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$port/mydatabase" \
  -e SPRING_DATASOURCE_USERNAME=myuser -e SPRING_DATASOURCE_PASSWORD="$DEV_POSTGRES_PASSWORD" \
  -e SHORTENER_SHORTLINK_TARGETURLS_ALLOWANY=true \
  -e SHORTENER_SECURITY_DPOP_NONCE_SECRET="$(openssl rand -hex 32)" \
  "$image" >/dev/null

for seconds in $(seq 120); do
  if curl -sf http://localhost:8081/actuator/health/readiness >/dev/null; then
    echo "start-image: ready after about $((seconds - 1)) seconds"
    exit 0
  fi
  [ "$(docker inspect --format '{{.State.Running}}' app)" = true ] || break
  sleep 1
done
echo "start-image: the application did not become ready; the end of its log:" >&2
docker logs --tail 40 app >&2
exit 1
