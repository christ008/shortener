#!/bin/sh
# Makes everything a rehearsal of compose.prod.yaml on one machine needs: throwaway random passwords, a self-signed
# certificate for localhost, and an .env that points the application at the Keycloak of compose.local.yaml. Nothing here
# is for a real deployment. Run it from the repository root; it refuses to overwrite an existing secrets directory.
set -eu
dir=${SECRETS_DIR:-secrets}
[ ! -e "$dir" ] || { echo "$dir already exists; remove it first" >&2; exit 1; }
mkdir -p "$dir"

for name in db_postgres_password db_app_password db_migrator_password db_exporter_password; do
  openssl rand -hex 24 | tr -d '\n' > "$dir/$name"
done

openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 30 \
  -subj '/CN=localhost' -addext 'subjectAltName=DNS:localhost,IP:127.0.0.1' \
  -keyout "$dir/tls_key" -out "$dir/tls_cert" 2>/dev/null

# Swarm mounts secrets with the mode of the source file; the containers run as other users than their owner here.
chmod 0444 "$dir"/*

if [ ! -e .env ]; then
  cat > .env <<ENV
SHORTENER_IMAGE=shortener
SHORTENER_VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
SHORTENER_MIGRATE_VERSION=$(sed -n 's/^version = "\(.*\)"/\1/p' build.gradle.kts)
ISSUER_URI=http://localhost:8180/realms/shortener
JWKS_URI=http://keycloak:8080/realms/shortener/protocol/openid-connect/certs
ENV
  echo "wrote .env"
fi
echo "wrote $dir; start with: docker compose -f compose.prod.yaml -f deploy/stack/local/compose.local.yaml up -d"
