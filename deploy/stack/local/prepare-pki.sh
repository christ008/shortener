#!/bin/sh
# Adds the stack's own CA to a rehearsal on one machine, to try mutual TLS between the services (docs/DEPLOY.md#mutual-tls-between-the-services). Run it
# from the repository root, after deploy/stack/local/prepare.sh and with Docker running. Not for a real deployment: the root, which is never on a node,
# is made here beside the CA, and every key is a file of this directory. It refuses to run over a CA that is made.
#
# It does what the operator does in three places, in order: makes a root (pki.sh root), makes the CA's key and configuration (pki.sh init), has the root
# sign the CA (pki.sh sign-intermediate and install-intermediate), starts the CA, and issues the four identities (pki.sh issue). Then it makes the
# files readable by the users the containers run as, which Swarm does with the owner and mode of each secret and `docker compose` cannot.
#
# Needs: the user that runs the containers' application, uid 1000, to be yours: the CA's database and the keys pgjdbc reads belong to it. The CA keeps
# its database in secrets/pki/db, which Git ignores, instead of /srv/shortener/pki.
set -eu

dir=${SECRETS_DIR:-secrets}
[ -f "$dir/tls_cert" ] || { echo "$dir has no secrets: run deploy/stack/local/prepare.sh first" >&2; exit 1; }
[ ! -e "$dir/pki_ca_json" ] || { echo "$dir has a CA already; remove its pki_* files, secrets/offline_pki and secrets/pki to start again" >&2; exit 1; }
[ "$(id -u)" = 1000 ] || { echo "run it as uid 1000: the CA and the application's keys belong to the user the containers run as" >&2; exit 1; }

SECRETS_DIR=$(cd "$dir" && pwd)
PKI_DIR=$SECRETS_DIR/pki
CA_NAME=stepca
export SECRETS_DIR PKI_DIR CA_NAME
# Every later `docker compose` needs the same directory for the CA's database, and Compose makes the one it is not told of, as root, under /srv.
grep -q '^PKI_DIR=' .env 2>/dev/null || echo "PKI_DIR=$PKI_DIR" >> .env

root=$SECRETS_DIR/offline_pki
deploy/stack/pki.sh root "$root"
deploy/stack/pki.sh init
deploy/stack/pki.sh sign-intermediate "$root" "$SECRETS_DIR/pki_ca.csr" "$root/ca.pem"
deploy/stack/pki.sh install-intermediate "$root/ca.pem" "$root/root.crt"

# Compose reads the hashes and labels of the files from the environment, as deploy.sh does.
pki_env=$(deploy/stack/pki.sh env)
# shellcheck disable=SC2086
export $pki_env
docker compose --env-file .env -f deploy/stack/compose.prod.yaml -f deploy/stack/overlays/compose.pki-ca.yaml up -d --wait stepca
for role in edge shortener migrator postgres; do
  deploy/stack/pki.sh issue "$role"
done

# The edge runs as 101 and the application as 1000, who own none of these in a bind mount. The two DER keys stay 0600: pgjdbc refuses a key others can read.
chmod 0444 "$SECRETS_DIR"/pki_*_cert "$SECRETS_DIR/pki_edge_key" "$SECRETS_DIR/pki_shortener_key"
echo "ready. Start the stack with the two overlays, after exporting what pki.sh env prints:"
echo "  export \$(deploy/stack/pki.sh env)"
echo "  docker compose --env-file .env -f deploy/stack/compose.prod.yaml -f deploy/stack/local/compose.local.yaml \\"
echo "    -f deploy/stack/overlays/compose.pki-ca.yaml -f deploy/stack/overlays/compose.pki.yaml up -d"
