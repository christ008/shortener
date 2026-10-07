#!/bin/sh
# Deploys or updates the production stack on Swarm. Run it from the repository root, on a manager, with .env and the secrets in
# place (docs/DEPLOY.md).
#
#   deploy/stack/deploy.sh VERSION [STACK]
#
# On an update the migration job runs first, then the application is updated. A first deploy starts everything at once.
#
# Settings, all optional:
#   COMPOSE_FILES   files to deploy, default "compose.prod.yaml". The overlays in deploy/stack/overlays/ are added with
#                   OBSERVABILITY=1 (compose.observability.yaml), KEYCLOAK=1 (compose.keycloak.yaml) and
#                   POSTGRES_HA=1 (compose.postgres-ha.yaml)
#   RESOLVE_IMAGE   `always` (default) asks the registry for the image digest, `never` uses what the node has
#   VERIFY_SIGNATURE  `always` (default) runs `cosign verify` on the image for VERSION, which must have been signed by the
#                   release workflow of its repository on ghcr.io, and deploys nothing when it fails. `never` skips it, for
#                   the rehearsal on one machine, whose image is built locally
#   WAIT            seconds to wait for the migration job, default 300
set -eu

version=${1:?usage: deploy/stack/deploy.sh VERSION [STACK]}
stack=${2:-shortener}

# `docker stack deploy` does not read .env, so load it here. Variables already in the environment win. A name that is not a
# plain variable name is refused, because the line is expanded by `eval`.
if [ -f .env ]; then
  # shellcheck disable=SC2034  # `value` is read by the eval below
  while IFS='=' read -r key value; do
    case "$key" in ''|'#'*) continue ;; esac
    case "$key" in
      [A-Za-z_]*) ;;
      *) echo ".env: '$key' is not a variable name" >&2; exit 1 ;;
    esac
    case "$key" in
      *[!A-Za-z0-9_]*) echo ".env: '$key' is not a variable name" >&2; exit 1 ;;
    esac
    value=${value#\'}
    value=${value%\'}
    eval "[ -n \"\${$key:-}\" ] || export $key=\"\$value\""
  done < .env
fi

if [ "${VERIFY_SIGNATURE:-always}" != never ]; then
  image=${SHORTENER_IMAGE:-ghcr.io/christ008/shortener}
  case "$image" in
    ghcr.io/*) ;;
    *) echo "cannot verify $image: only images of ghcr.io are signed by the release workflow (VERIFY_SIGNATURE=never skips the check)" >&2; exit 1 ;;
  esac
  command -v cosign >/dev/null 2>&1 || { echo "cosign is needed to verify the image (VERIFY_SIGNATURE=never skips the check)" >&2; exit 1; }
  echo "verifying the signature of $image:$version"
  cosign verify "$image:$version" \
    --certificate-identity "https://github.com/${image#ghcr.io/}/.github/workflows/release.yml@refs/tags/v$version" \
    --certificate-oidc-issuer https://token.actions.githubusercontent.com >/dev/null \
    || { echo "the signature of $image:$version could not be verified; nothing was deployed" >&2; exit 1; }
fi

files=${COMPOSE_FILES:-compose.prod.yaml}
[ -z "${OBSERVABILITY:-}" ] || files="$files deploy/stack/overlays/compose.observability.yaml"
[ -z "${KEYCLOAK:-}" ] || files="$files deploy/stack/overlays/compose.keycloak.yaml"
[ -z "${POSTGRES_HA:-}" ] || files="$files deploy/stack/overlays/compose.postgres-ha.yaml"
args=""
for f in $files; do args="$args -c $f"; done

# Config names carry a hash of the file.
NGINX_CONF_HASH=$(sha256sum deploy/edge/nginx.conf | cut -c1-12)
PROMETHEUS_CONF_HASH=$(cat deploy/observability/prometheus.stack.yml deploy/observability/alerts.yml | sha256sum | cut -c1-12)
if [ -n "${KEYCLOAK:-}" ]; then
  KEYCLOAK_REALM_HASH=$(sha256sum deploy/keycloak/shortener-realm.production.json | cut -c1-12)
  EDGE_KEYCLOAK_CONF_HASH=$(sha256sum deploy/edge/keycloak.conf | cut -c1-12)
  export KEYCLOAK_REALM_HASH EDGE_KEYCLOAK_CONF_HASH
fi
export NGINX_CONF_HASH PROMETHEUS_CONF_HASH

deploy() {
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
