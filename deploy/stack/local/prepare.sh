#!/bin/sh
# Makes what a rehearsal of compose.prod.yaml on one machine needs: throwaway random passwords, a self-signed certificate for
# localhost, and the settings that point the application at the Keycloak of compose.local.yaml. Not for a real deployment. Run
# it from the repository root. It refuses to overwrite an existing secrets directory.
set -eu
dir=${SECRETS_DIR:-secrets}
[ ! -e "$dir" ] || { echo "$dir already exists; remove it first" >&2; exit 1; }
mkdir -p "$dir"

for name in db_postgres_password db_app_password db_migrator_password db_exporter_password dpop_nonce_secret; do
  openssl rand -hex 24 | tr -d '\n' > "$dir/$name"
done

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -keyout "$dir/tls_key" -out "$dir/tls_cert" 2>/dev/null

# Readable by the containers, which run as other users.
chmod 0444 "$dir"/*

# The rehearsal uses the dev realm and its Keycloak password, which deploy/keycloak/dev-setup makes.
if [ ! -f deploy/keycloak/shortener-realm.json ] || ! grep -q '^DEV_KEYCLOAK_ADMIN_PASSWORD=' .env 2>/dev/null; then
  deploy/keycloak/dev-setup --yes
fi

# Adds the settings the stack needs to .env, and leaves what is there, such as the dev passwords, alone.
version=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
for line in "SHORTENER_IMAGE=shortener" "VERIFY_SIGNATURE=never" "SHORTENER_VERSION=$version" "SHORTENER_MIGRATE_VERSION=$version" \
  "ISSUER_URI=http://localhost:8180/realms/shortener" \
  "JWKS_URI=http://keycloak:8080/realms/shortener/protocol/openid-connect/certs"; do
  grep -q "^${line%%=*}=" .env 2>/dev/null || echo "$line" >>.env
done
echo "updated .env"
echo "wrote $dir; start with: docker compose --env-file .env -f deploy/stack/compose.prod.yaml -f deploy/stack/local/compose.local.yaml up -d"
