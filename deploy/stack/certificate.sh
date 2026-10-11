#!/bin/sh
# Issues the edge's certificate with Let's Encrypt and keeps it renewed. Run it as root, on the node that runs the edge, from anywhere:
# the repository is found from the script's own path.
#
#   deploy/stack/certificate.sh placeholder HOST       a self-signed certificate in secrets/, so that the edge can start
#   deploy/stack/certificate.sh issue HOST EMAIL       asks Let's Encrypt for HOST, installs it, and sets up the renewal. EMAIL `-` registers
#                                                      without one, and Let's Encrypt cannot warn you before an expiry
#   deploy/stack/certificate.sh install LINEAGE        copies a certificate into secrets/ and deploys again (what the renewal runs)
#
# The order on a fresh node (docs/DEPLOY.md#certificate):
#   1. placeholder, because the edge does not start without a certificate and cannot answer a challenge until it runs
#   2. ACME=1 deploy/stack/deploy.sh VERSION, which mounts the challenge directory in the edge
#   3. issue, once DNS for HOST points at this node and port 80 is open
#
# `issue` over a certificate that came from the other service (staging, then real) replaces it; over one from the same service, certbot keeps it
# until it is due for renewal. `issue` uses HTTP-01 through the edge on port 80 (`certbot --webroot`), so no wildcard names. Certbot's own timer renews the
# certificate about thirty days before it expires and runs the hook that `issue` installs, which calls `install`. A secret is
# immutable, so `install` writes the new files and deploys again: the secrets are named after their contents (TLS_HASH) and the edge
# is replaced, which takes a few seconds on one node. The deploy reads the overlay flags (ACME, KEYCLOAK, ...) from .env, so keep them
# there, and keeps the running version.
#
# Settings, all optional:
#   ACME_DIR   directory of the challenges, default /srv/shortener/acme (the same as the overlay's)
#   STACK      name of the stack, default shortener
#   STAGING=1  uses Let's Encrypt's staging service, whose certificates no client trusts and whose limits are generous
set -eu

[ "$(id -u)" -eq 0 ] || { echo "run it as root (sudo)" >&2; exit 1; }
repo=$(cd "$(dirname "$0")/../.." && pwd)
secrets=${SECRETS_DIR:-$repo/secrets}
acme=${ACME_DIR:-/srv/shortener/acme}
stack=${STACK:-shortener}
hook=/etc/letsencrypt/renewal-hooks/deploy/shortener

command=${1:?usage: deploy/stack/certificate.sh placeholder HOST | issue HOST EMAIL | install LINEAGE}

case "$command" in
  placeholder)
    host=${2:?usage: deploy/stack/certificate.sh placeholder HOST}
    [ ! -e "$secrets/tls_cert" ] || { echo "$secrets/tls_cert exists; nothing to do" >&2; exit 0; }
    mkdir -p "$secrets" "$acme"
    chmod 0755 "$acme"
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 -subj "/CN=$host" \
      -addext "subjectAltName=DNS:$host" -keyout "$secrets/tls_key" -out "$secrets/tls_cert" 2>/dev/null
    chmod 0444 "$secrets/tls_key" "$secrets/tls_cert"
    echo "wrote a self-signed certificate for $host in $secrets; deploy with ACME=1, then run issue"
    ;;

  issue)
    host=${2:?usage: deploy/stack/certificate.sh issue HOST EMAIL}
    email=${3:?usage: deploy/stack/certificate.sh issue HOST EMAIL}
    command -v certbot >/dev/null 2>&1 || { echo "certbot is not installed (apt install certbot)" >&2; exit 1; }
    [ -d "$acme" ] || { echo "$acme does not exist: run placeholder first" >&2; exit 1; }
    docker service ls --format '{{.Name}}' | grep -qx "${stack}_edge" \
      || { echo "the edge of stack $stack is not running: deploy it with ACME=1 first" >&2; exit 1; }
    docker service inspect --format '{{range .Spec.TaskTemplate.ContainerSpec.Mounts}}{{.Target}} {{end}}' "${stack}_edge" \
      | grep -q /var/www/acme || { echo "the edge does not mount the challenge directory: deploy it with ACME=1" >&2; exit 1; }
    # Certbot keeps a certificate that is not due for renewal, whoever issued it, so a staging one would stay in place of a real one (and the
    # reverse). When the service asked for differs from the one the existing certificate came from, issue anyway.
    renewal=/etc/letsencrypt/renewal/$host.conf
    force=
    if [ -f "$renewal" ]; then
      staged=
      ! grep -q '^server *=.*staging' "$renewal" || staged=1
      [ "$staged" = "${STAGING:+1}" ] || force=--force-renewal
    fi
    # shellcheck disable=SC2046,SC2086
    certbot certonly --webroot -w "$acme" -d "$host" --agree-tos --non-interactive $force \
      $([ "$email" != - ] && echo "--email $email" || echo --register-unsafely-without-email) \
      $([ -z "${STAGING:-}" ] || echo --test-cert)
    mkdir -p "$(dirname "$hook")"
    printf '#!/bin/sh\nexec "%s/deploy/stack/certificate.sh" install "$RENEWED_LINEAGE"\n' "$repo" >"$hook"
    chmod 0755 "$hook"
    "$0" install "/etc/letsencrypt/live/$host"
    ;;

  install)
    lineage=${2:?usage: deploy/stack/certificate.sh install LINEAGE}
    [ -r "$lineage/fullchain.pem" ] && [ -r "$lineage/privkey.pem" ] || { echo "no certificate in $lineage" >&2; exit 1; }
    install -m 0444 "$lineage/fullchain.pem" "$secrets/tls_cert"
    install -m 0444 "$lineage/privkey.pem" "$secrets/tls_key"
    version=$(docker service inspect --format '{{.Spec.TaskTemplate.ContainerSpec.Image}}' "${stack}_shortener" 2>/dev/null \
      | sed 's/@.*//; s/.*://' || true)
    [ -n "$version" ] || { echo "the stack $stack is not deployed; the files are in place, deploy.sh will use them" >&2; exit 0; }
    cd "$repo"
    deploy/stack/deploy.sh "$version" "$stack"
    ;;

  *)
    echo "usage: deploy/stack/certificate.sh placeholder HOST | issue HOST EMAIL | install LINEAGE" >&2
    exit 1
    ;;
esac
