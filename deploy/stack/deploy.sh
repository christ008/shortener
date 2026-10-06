#!/bin/sh
# Deploys or updates the production stack on Swarm. Run it from the repository root, on a manager, with .env and the
# secrets in place (docs/DEPLOY.md).
#
#   deploy/stack/deploy.sh VERSION [STACK]
#
# On an update the migration job goes first, while the application is still on the version it runs now: migrations are
# written so that the previous version keeps working against the new schema (add, then switch, then remove), so the
# application is only updated once the job has finished. A first deploy starts everything at once, and the application
# restarts until the schema exists.
#
# Settings, all optional:
#   COMPOSE_FILES   files to deploy, default "compose.prod.yaml"; OBSERVABILITY=1 adds compose.prod.observability.yaml and
#                   KEYCLOAK=1 adds compose.prod.keycloak.yaml, and POSTGRES_HA=1 adds compose.prod.postgres-ha.yaml
#   RESOLVE_IMAGE   `always` (default) asks the registry for the image digest, `never` uses what the node has
#   WAIT            seconds to wait for the migration job, default 300
set -eu

version=${1:?usage: deploy/stack/deploy.sh VERSION [STACK]}
stack=${2:-shortener}
files=${COMPOSE_FILES:-compose.prod.yaml}
[ -z "${OBSERVABILITY:-}" ] || files="$files compose.prod.observability.yaml"
[ -z "${KEYCLOAK:-}" ] || files="$files compose.prod.keycloak.yaml"
[ -z "${POSTGRES_HA:-}" ] || files="$files compose.prod.postgres-ha.yaml"
args=""
for f in $files; do args="$args -c $f"; done

# Swarm configs cannot change once created, so their names carry a hash of the file.
NGINX_CONF_HASH=$(sha256sum deploy/edge/nginx.conf | cut -c1-12)
PROMETHEUS_CONF_HASH=$(cat deploy/observability/prometheus.stack.yml deploy/observability/alerts.yml | sha256sum | cut -c1-12)
if [ -n "${KEYCLOAK:-}" ]; then
  KEYCLOAK_REALM_HASH=$(sha256sum deploy/keycloak/shortener-realm.production.json | cut -c1-12)
  EDGE_KEYCLOAK_CONF_HASH=$(sha256sum deploy/edge/keycloak.conf | cut -c1-12)
  export KEYCLOAK_REALM_HASH EDGE_KEYCLOAK_CONF_HASH
fi
export NGINX_CONF_HASH PROMETHEUS_CONF_HASH

deploy() {
  # `docker stack deploy` does not read .env, so load it here. Variables already in the environment win.
  if [ -f .env ]; then
    # shellcheck disable=SC2034  # `value` is read by the eval below
    while IFS='=' read -r key value; do
      case "$key" in ''|'#'*) continue ;; esac
      value=${value#\'}
      value=${value%\'}
      eval "[ -n \"\${$key:-}\" ] || export $key=\"\$value\""
    done < .env
  fi
  # shellcheck disable=SC2086
  docker stack deploy $args --with-registry-auth --resolve-image "${RESOLVE_IMAGE:-always}" --detach=true "$stack"
}

current=$(docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' "${stack}_shortener" 2>/dev/null \
  | sed 's/@.*//; s/.*://' || true)

if [ -z "$current" ]; then
  SHORTENER_VERSION=$version SHORTENER_MIGRATE_VERSION=$version deploy
  echo "deployed $version; the application restarts until the migration job has finished"
  exit 0
fi

tag_of() { docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' "${stack}_$1" 2>/dev/null | sed 's/@.*//; s/.*://' || true; }
iteration() { docker service inspect --format '{{.JobStatus.JobIteration.Index}}' "${stack}_migrate" 2>/dev/null || echo 0; }

if [ "$(tag_of migrate)" = "$version" ]; then
  echo "the migration job is already at $version"
else
  echo "running version $current; migrating to $version first"
  before=$(iteration)
  SHORTENER_VERSION=$current SHORTENER_MIGRATE_VERSION=$version deploy
  waited=0
  # A job service runs again when its spec changes, and counts that as a new iteration.
  until [ "$(iteration)" -gt "$before" ] && docker service ps "${stack}_migrate" --format '{{.CurrentState}}' | head -1 | grep -q '^Complete'; do
    if [ "$waited" -ge "${WAIT:-300}" ]; then
      echo "the migration did not finish in ${WAIT:-300} s; its log:" >&2
      docker service logs --tail 30 "${stack}_migrate" >&2 || true
      exit 1
    fi
    sleep 3
    waited=$((waited + 3))
  done
fi
echo "migrated; updating the application to $version"

SHORTENER_VERSION=$version SHORTENER_MIGRATE_VERSION=$version deploy
echo "updating; follow it with: docker service ps ${stack}_shortener"
